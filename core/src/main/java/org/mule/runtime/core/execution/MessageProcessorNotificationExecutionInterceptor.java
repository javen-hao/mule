/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.execution;

import static org.mule.runtime.core.DefaultMuleEvent.setCurrentEvent;

import org.mule.runtime.core.DefaultMuleEvent;
import org.mule.runtime.core.NonBlockingVoidMuleEvent;
import org.mule.runtime.core.api.MessagingException;
import org.mule.runtime.core.api.MuleEvent;
import org.mule.runtime.core.api.MuleException;
import org.mule.runtime.core.api.MuleMessage;
import org.mule.runtime.core.api.connector.NonBlockingReplyToHandler;
import org.mule.runtime.core.api.connector.ReplyToHandler;
import org.mule.runtime.core.api.construct.FlowConstruct;
import org.mule.runtime.core.api.construct.MessageProcessorPathResolver;
import org.mule.runtime.core.api.processor.InterceptingMessageProcessor;
import org.mule.runtime.core.api.processor.MessageProcessor;
import org.mule.runtime.core.context.notification.MessageProcessorNotification;
import org.mule.runtime.core.context.notification.ServerNotificationManager;
import org.mule.runtime.core.processor.NonBlockingMessageProcessor;

/**
 * Intercepts MessageProcessor execution to fire before and after notifications
 */
class MessageProcessorNotificationExecutionInterceptor implements MessageProcessorExecutionInterceptor {

  private MessageProcessorExecutionInterceptor next;

  MessageProcessorNotificationExecutionInterceptor(MessageProcessorExecutionInterceptor next) {
    this.next = next;
  }

  MessageProcessorNotificationExecutionInterceptor() {

  }


  @Override
  public MuleEvent execute(final MessageProcessor messageProcessor, final MuleEvent event) throws MessagingException {
    final ServerNotificationManager notificationManager = event.getMuleContext().getNotificationManager();
    final boolean fireNotification = event.isNotificationsEnabled();
    if (fireNotification) {
      fireNotification(notificationManager, event.getFlowConstruct(), event, messageProcessor,
                       null, MessageProcessorNotification.MESSAGE_PROCESSOR_PRE_INVOKE);
    }

    MuleEvent eventToProcess = event;
    MuleEvent result = null;
    MessagingException exceptionThrown = null;

    boolean nonBlocking = event.isAllowNonBlocking() && event.getReplyToHandler() != null;
    boolean responseProcessing = messageProcessor instanceof InterceptingMessageProcessor ||
        messageProcessor instanceof NonBlockingMessageProcessor;

    if (nonBlocking && responseProcessing) {
      final ReplyToHandler originalReplyToHandler = event.getReplyToHandler();
      eventToProcess = new DefaultMuleEvent(event, new NonBlockingReplyToHandler() {

        @Override
        public void processReplyTo(MuleEvent result, MuleMessage returnMessage, Object replyTo) throws MuleException {

          if (fireNotification) {
            fireNotification(notificationManager, event.getFlowConstruct(), result != null ? result : event,
                             messageProcessor,
                             null, MessageProcessorNotification.MESSAGE_PROCESSOR_POST_INVOKE);
          }
          originalReplyToHandler.processReplyTo(result, returnMessage, replyTo);
        }

        @Override
        public void processExceptionReplyTo(MessagingException exception, Object replyTo) {
          if (fireNotification) {
            MuleEvent result = exception.getEvent();
            fireNotification(notificationManager, event.getFlowConstruct(), result != null ? result : event,
                             messageProcessor,
                             null, MessageProcessorNotification.MESSAGE_PROCESSOR_POST_INVOKE);
          }
          originalReplyToHandler.processExceptionReplyTo(exception, replyTo);
        }
      });
    }
    // Update RequestContext ThreadLocal in case if previous processor modified it
    // also for backwards compatibility
    setCurrentEvent(eventToProcess);

    try {
      if (next == null) {
        result = messageProcessor.process(eventToProcess);
      } else {
        result = next.execute(messageProcessor, eventToProcess);
      }
    } catch (MessagingException e) {
      exceptionThrown = e;
      throw e;
    } catch (MuleException e) {
      exceptionThrown = new MessagingException(event, e, messageProcessor);
      throw exceptionThrown;
    } finally {
      if (!NonBlockingVoidMuleEvent.getInstance().equals(result) && fireNotification) {
        fireNotification(notificationManager, event.getFlowConstruct(), result != null ? result : event,
                         messageProcessor,
                         exceptionThrown, MessageProcessorNotification.MESSAGE_PROCESSOR_POST_INVOKE);
      }
    }
    return result;
  }

  protected void fireNotification(ServerNotificationManager serverNotificationManager, FlowConstruct flowConstruct,
                                  MuleEvent event, MessageProcessor processor, MessagingException exceptionThrown, int action) {
    if (serverNotificationManager != null
        && serverNotificationManager.isNotificationEnabled(MessageProcessorNotification.class)) {
      if (flowConstruct instanceof MessageProcessorPathResolver
          && ((MessageProcessorPathResolver) flowConstruct).getProcessorPath(processor) != null) {
        serverNotificationManager
            .fireNotification(new MessageProcessorNotification(flowConstruct, event, processor, exceptionThrown, action));
      }
    }
  }
}
