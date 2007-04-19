/*
 * $Id$
 * --------------------------------------------------------------------------------------
 * Copyright (c) MuleSource, Inc.  All rights reserved.  http://www.mulesource.com
 *
 * The software in this package is published under the terms of the MuleSource MPL
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.examples.loanbroker.esn;

import org.mule.examples.loanbroker.tests.AbstractAsynchronousLoanBrokerTestCase;


public class XFireLoanBrokerAsynchronousFunctionalTestCase extends AbstractAsynchronousLoanBrokerTestCase
{
    // @Override
    protected String getConfigResources()
    {
        return "loan-broker-xfire-endpoints-config.xml, loan-broker-async-config.xml";
    }
}
