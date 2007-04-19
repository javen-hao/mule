/*
 * $Id$
 * --------------------------------------------------------------------------------------
 * Copyright (c) MuleSource, Inc.  All rights reserved.  http://www.mulesource.com
 *
 * The software in this package is published under the terms of the MuleSource MPL
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.routing.filters;

import org.mule.config.ConfigurationException;
import org.mule.impl.MuleMessage;
import org.mule.tck.AbstractMuleTestCase;
import org.mule.umo.UMOMessage;

public class OGNLFilterTestCase extends AbstractMuleTestCase
{
    private OGNLFilter filter;

    // @Override
    protected void doSetUp() throws Exception
    {
        filter = new OGNLFilter();
    }

    // @Override
    protected void doTearDown() throws Exception
    {
        filter = null;
    }

    public void testNewFilter()
    {
        assertFalse(filter.accept(null));
    }

    public void testNoExpressionEmptyMessage()
    {
        UMOMessage message = new MuleMessage(null);
        assertFalse(filter.accept(message));
    }

    public void testNoExpressionValidMessage()
    {
        UMOMessage message = new MuleMessage("foo");
        assertFalse(filter.accept(message));
    }

    public void testStringExpression() throws ConfigurationException
    {
        UMOMessage message = new MuleMessage("foo");
        filter.setExpression("equals(\"foo\")");
        assertTrue(filter.accept(message));
    }

    public void testValidObjectExpression() throws ConfigurationException
    {
        Dummy payload = new Dummy();
        payload.setContent("foobar");
        UMOMessage message = new MuleMessage(payload);
        filter.setExpression("content.endsWith(\"bar\")");
        assertTrue(filter.accept(message));
    }

    public void testInvalidObjectExpression()
    {
        try
        {
            filter.setExpression("foo:bar");
            fail("should have failed with ConfigurationException");
        }
        catch (ConfigurationException configex)
        {
            // expected
        }

        // make sure the filter is still unconfigured
        assertNull(filter.getExpression());
    }

    // a simple POJO for testing object expressions
    private static class Dummy
    {
        private int id;
        private String content;

        public Dummy()
        {
            super();
        }

        /**
         * @return Returns the content.
         */
        public String getContent()
        {
            return content;
        }

        /**
         * @param content The content to set.
         */
        public void setContent(String content)
        {
            this.content = content;
        }

        /**
         * @return Returns the id.
         */
        public int getId()
        {
            return id;
        }

        /**
         * @param id The id to set.
         */
        public void setId(int id)
        {
            this.id = id;
        }
    }

}
