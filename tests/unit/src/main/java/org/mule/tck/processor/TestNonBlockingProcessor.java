/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.tck.processor;

import org.mule.runtime.core.api.MuleEvent;
import org.mule.runtime.core.api.MuleException;
import org.mule.runtime.core.api.processor.MessageProcessor;

/**
 * Test implementation of a blocking {@link MessageProcessor}
 */
public class TestNonBlockingProcessor implements MessageProcessor {

  @Override
  public MuleEvent process(final MuleEvent event) throws MuleException {
    return event;
  }

  @Override
  public boolean isBlocking() {
    return true;
  }
}
