/*
 * $Id$
 * --------------------------------------------------------------------------------------
 * Copyright (c) MuleSource, Inc.  All rights reserved.  http://www.mulesource.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.transaction;

import org.mule.api.MuleContext;
import org.mule.api.transaction.ExternalTransactionAwareTransactionFactory;
import org.mule.api.transaction.Transaction;
import org.mule.api.transaction.TransactionCallback;
import org.mule.api.transaction.TransactionConfig;
import org.mule.api.transaction.TransactionException;
import org.mule.api.transaction.TransactionFactory;
import org.mule.config.i18n.CoreMessages;
import org.mule.transport.ConnectException;
import org.mule.util.ExceptionUtils;
import org.mule.util.MuleExceptionHandlingUtil;

import java.beans.ExceptionListener;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class TransactionTemplate
{
    private static final Log logger = LogFactory.getLog(TransactionTemplate.class);

    private final TransactionConfig config;
    private final ExceptionListener exceptionListener;
    private final MuleContext context;

    public TransactionTemplate(TransactionConfig config, ExceptionListener listener, MuleContext context)
    {
        this.config = config;
        exceptionListener = listener;
        this.context = context;
    }

    public Object execute(TransactionCallback callback) throws Exception
    {
        //if we want to skip TT
        if (config == null)
        {
            return callback.doInTransaction();
        }

        Transaction joinedExternal = null;
        byte action = (config != null) ? config.getAction() : TransactionConfig.ACTION_DEFAULT;
        Transaction tx = TransactionCoordination.getInstance().getTransaction();
        if (tx == null && context != null && config != null && config.isInteractWithExternal())
        {

            TransactionFactory tmFactory = config.getFactory();
            if (tmFactory instanceof ExternalTransactionAwareTransactionFactory)
            {
                ExternalTransactionAwareTransactionFactory extmFactory =
                    (ExternalTransactionAwareTransactionFactory) tmFactory;
                joinedExternal = tx = extmFactory.joinExternalTransaction(context);
            }
        }

        Transaction suspendedXATx = null;
        
        if (action == TransactionConfig.ACTION_NEVER && tx != null)
        {
            throw new IllegalTransactionStateException(
                CoreMessages.transactionAvailableButActionIs("Never"));
        }
        else if ((action == TransactionConfig.ACTION_NONE || action == TransactionConfig.ACTION_ALWAYS_BEGIN)
                   && tx != null)
        {
            if (logger.isDebugEnabled())
            {
                logger.debug(action + ", " + "current TX: " + tx);
            }

            if (tx.isXA())
            {
                // suspend current transaction
                suspendedXATx = tx;
                suspendXATransaction(suspendedXATx);
            }
            else
            {
                // commit/rollback
                resolveTransaction(tx);
            }
            //transaction will be started below
            tx = null;
        }
        else if (action == TransactionConfig.ACTION_ALWAYS_JOIN && tx == null)
        {
            throw new IllegalTransactionStateException(
                CoreMessages.transactionNotAvailableButActionIs("Always Join"));
        }

        if (action == TransactionConfig.ACTION_ALWAYS_BEGIN
            || (action == TransactionConfig.ACTION_BEGIN_OR_JOIN && tx == null))
        {
            logger.debug("Beginning transaction");
            tx = config.getFactory().beginTransaction(context);
            logger.debug("Transaction successfully started: " + tx);
        }
        else
        {
            tx = null;
        }

        try
        {
            Object result = callback.doInTransaction();
            if (tx != null)
            {
                //verify that transaction is still active
                tx = TransactionCoordination.getInstance().getTransaction();
            }
            if (tx != null)
            {
                resolveTransaction(tx);
            }
            if (suspendedXATx != null)
            {
                resumeXATransaction(suspendedXATx);
                tx = suspendedXATx;
            }
            return result;
        }
        catch (Exception e)
        {
            tx = TransactionCoordination.getInstance().getTransaction();
            return handleException(e, tx, suspendedXATx);
        }
        catch (Error e)
        {
            return handleError(e, tx);
        }
        finally
        {
            if (joinedExternal != null)
                TransactionCoordination.getInstance().unbindTransaction(joinedExternal);
        }
    }


    Object handleException(Exception e, Transaction tx, Transaction suspendedXATx)
        throws TransactionException, Exception
    {
        if (isExceptionHandledAtThisLevel(tx))
        {
            logger.info("Exception Caught in Transaction template.  Handing off to exception handler: "
                + exceptionListener);

            MuleExceptionHandlingUtil.handledExceptionIfNeeded(exceptionListener, e);
        }
        else
        {
            logger.info("Exception Caught in Transaction template without any exception listeners defined, exception is rethrown.");
            if (tx != null)
            {
                tx.setRollbackOnly();
            }
        }
        if (tx != null)
        {
            // The exception strategy can choose to route exception
            // messages as part of the current transaction. So only rollback the
            // tx if it has been marked for rollback (which is the default
            // case in the AbstractExceptionListener)
            if (tx.isRollbackOnly())
            {
                logger.debug("Exception caught: rollback transaction", e);
            }
            resolveTransaction(tx);
        }
        if (suspendedXATx != null)
        {
            resumeXATransaction(suspendedXATx);
            // we've handled this exception above. just return null now, this way we isolate
            // the context delimited by XA's ALWAYS_BEGIN
            return null;
        }
        else if (ExceptionUtils.containsType(e, ConnectException.class))
        {
            // If the exception is caused by a Connection problem, we need to rethrow
            // the exception so any retry logic
            // that we may have can pick this up and start retrying.
            int exceptionIndex = ExceptionUtils.indexOfType(e, ConnectException.class);
            throw (ConnectException) ExceptionUtils.getThrowableList(e).get(exceptionIndex);
        }
        else if (isExceptionHandledAtThisLevel(tx))
        {
            // if exception is handled at this level, it has been handled
            // already, don't loop
            return null;
        }
        else
        {
            throw e;
        }
    }

    Object handleError(Error e, Transaction tx) throws TransactionException, Error
    {
        if (tx != null)
        {
            logger.info("Error caught, rolling back TX " + tx, e);
            tx.rollback();
        }
        throw e;
    }

    /**
     * The exception must be handled at this level if there is an
     * {@link #exceptionListener} and there is a transaction.
     * 
     * @param tx
     * @return
     */
    private boolean isExceptionHandledAtThisLevel(Transaction tx)
    {
        return exceptionListener != null && tx != null;
    }

    protected void resolveTransaction(Transaction tx) throws TransactionException
    {
        if (tx.isRollbackOnly())
        {
            if (logger.isDebugEnabled())
            {
                logger.debug("Transaction has been marked rollbackOnly, rolling it back: " + tx);
            }
            tx.rollback();
        }
        else
        {
            if (logger.isDebugEnabled())
            {
                logger.debug("Committing transaction " + tx);
            }
            tx.commit();
        }
    }

    protected void suspendXATransaction(Transaction tx) throws TransactionException
    {
        if (logger.isDebugEnabled())
        {
            logger.debug("Suspending " + tx);
        }

        tx.suspend();

        if (logger.isDebugEnabled())
        {
            logger.debug("Successfully suspended " + tx);
            logger.debug("Unbinding the following TX from the current context: " + tx);
        }

        TransactionCoordination.getInstance().unbindTransaction(tx);
    }

    protected void resumeXATransaction(Transaction tx) throws TransactionException
    {
        if (logger.isDebugEnabled())
        {
            logger.debug("Re-binding and Resuming " + tx);
        }

        TransactionCoordination.getInstance().bindTransaction(tx);
        tx.resume();
    }

}
