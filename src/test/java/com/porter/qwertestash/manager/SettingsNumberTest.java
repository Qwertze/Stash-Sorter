package com.porter.qwertestash.manager;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SettingsNumberTest {
    public static class Values {
        public int ticks;
        public long timestamp;
        public byte small;
        public float ratio;
        public double seconds;
    }

    private Object validate(String field, String input) throws ReflectiveOperationException {
        return ConfigApplier.validate(Values.class.getField(field), new SettingsSpec.Spec("fixture", "", ""),
            JsonParser.parseString(input));
    }

    @Test
    void validatesTheExactDecimalBeforeConvertingIntegerValues() throws Exception {
        assertEquals(1.75, validate("seconds", "1.75"));
        assertThrows(IllegalArgumentException.class, () -> validate("ticks", "2147483648"));
        assertEquals(20, validate("ticks", "2e1"));
        assertThrows(IllegalArgumentException.class, () -> validate("ticks", "20.0000000000000000001"));
        assertEquals(9007199254740993L, validate("timestamp", "9007199254740993"));
        assertEquals(Long.MAX_VALUE, validate("timestamp", "9223372036854775807"));
        assertThrows(IllegalArgumentException.class, () -> validate("timestamp", "9223372036854775808"));
    }

    @Test
    void valuesMustFitTheActualField() throws Exception {
        assertEquals((byte) 127, validate("small", "127"));
        assertThrows(IllegalArgumentException.class, () -> validate("small", "128"));
        assertEquals(1.25f, validate("ratio", "1.25"));
        assertThrows(IllegalArgumentException.class, () -> validate("ratio", "1e40"));
    }
}
