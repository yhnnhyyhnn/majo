package com.agent.coding.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for WebFetchTool's dependency-free HTML→text extraction.
 */
class WebFetchToolTest {

    @Test
    void stripsScriptAndStyle() {
        String html = "<html><head><style>body{color:red}</style></head>"
                + "<body><script>alert('x')</script><p>Hello</p></body></html>";
        String text = WebFetchTool.htmlToText(html);
        assertFalse(text.contains("alert"), text);
        assertFalse(text.contains("color:red"), text);
        assertTrue(text.contains("Hello"), text);
    }

    @Test
    void convertsBreaksAndBlocksToNewlines() {
        String text = WebFetchTool.htmlToText("a<br>b<p>c</p><div>d</div>");
        assertTrue(text.contains("a\nb"), text);
        assertTrue(text.contains("c\n\nd") || text.contains("c\nd"), text);
    }

    @Test
    void decodesCommonEntities() {
        String text = WebFetchTool.htmlToText("Tom &amp; Jerry &lt;tag&gt; &quot;q&quot; &nbsp;x");
        assertEquals("Tom & Jerry <tag> \"q\" x", text);
    }

    @Test
    void plainTextPassesThrough() {
        assertEquals("hello world", WebFetchTool.htmlToText("hello world"));
    }

    @Test
    void collapsesExcessWhitespaceAndBlankLines() {
        String text = WebFetchTool.htmlToText("  a  b\t\n\n\n\n\n c  ");
        assertEquals("a b\n\nc", text);
    }
}
