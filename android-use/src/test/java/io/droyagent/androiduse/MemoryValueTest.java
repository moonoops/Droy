package io.droyagent.androiduse;

import static org.junit.Assert.*;
import org.junit.Test;

public class MemoryValueTest {
    @Test public void preservesSignedAndUnsigned64BitValuesWithoutJavaScriptRounding() throws Exception {
        for (String value : new String[]{"9007199254740993", "9223372036854775807", "-9223372036854775808"})
            assertEquals(value, MemoryValue.decode("int64", MemoryValue.encode("int64", value), 0));
        assertEquals("18446744073709551615", MemoryValue.decode("uint64", MemoryValue.encode("uint64", "18446744073709551615"), 0));
        assertEquals("4294967295", MemoryValue.decode("uint32", MemoryValue.encode("uint32", "4294967295"), 0));
        assertEquals("ffffffff", MemoryValue.hex(MemoryValue.encode("int32", "-1")));
    }
    @Test public void rejectsOverflowAndNonFiniteOrAmbiguousInputs() throws Exception {
        Object[][] bad = {{"int32", "2147483648"}, {"uint32", "-1"}, {"int64", "9223372036854775808"},
            {"uint64", "18446744073709551616"}, {"int64", 9007199254740992L}, {"float32", "NaN"},
            {"float64", "Infinity"}, {"float32", "1e100"}, {"bytes", "xyz"}, {"bytes", "0"}};
        for (Object[] item : bad) {
            try { MemoryValue.encode((String) item[0], item[1]); fail("Expected invalid " + item[0]); }
            catch (ActionException expected) { assertEquals("invalid_argument", expected.getCode()); }
        }
    }
    @Test public void arm64EncodingAndAddressParsingAreExplicit() throws Exception {
        assertEquals("0000803f", MemoryValue.hex(MemoryValue.encode("float32", "1")));
        assertEquals("00000080", MemoryValue.hex(MemoryValue.encode("float32", "-0.0")));
        assertEquals("0000000000000080", MemoryValue.hex(MemoryValue.encode("float64", "-0.0")));
        assertEquals("1.0", MemoryValue.decode("float64", MemoryValue.encode("float64", "1.0"), 0));
        assertEquals(0x123456789abL, MemoryValue.address("0x123456789ab"));
        try { MemoryValue.address((Object) 12345); fail("Expected string address"); }
        catch (ActionException expected) { assertEquals("invalid_argument", expected.getCode()); }
    }
}
