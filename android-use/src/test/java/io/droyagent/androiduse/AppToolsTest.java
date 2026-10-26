package io.droyagent.androiduse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import org.junit.Test;

public class AppToolsTest {
    @Test public void acceptsLocalizedNamesAndPackageQueries() throws Exception {
        assertEquals("淘宝", AppTools.query("  淘宝  ", "appName"));
        assertEquals("com.example.reader", AppTools.query("com.example.reader", "query"));
    }

    @Test public void rejectsEmptyMalformedAndOversizedQueriesBeforePackageLookup() throws Exception {
        for (Object value : new Object[]{null, 5, "", "  \n\t", "a\0b", "a".repeat(201)}) {
            try {
                AppTools.query(value, "query");
                fail("Expected query validation failure");
            } catch (ActionException error) {
                assertEquals("invalid_argument", error.getCode());
            }
        }
    }
}
