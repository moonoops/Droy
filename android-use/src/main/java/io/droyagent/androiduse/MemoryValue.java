package io.droyagent.androiduse;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Set;

/** Explicit little-endian scalar encoding for the supported arm64 target ABI. */
final class MemoryValue {
    static final Set<String> TYPES = Set.of("int32", "uint32", "int64", "uint64", "float32", "float64", "bytes");
    private MemoryValue() {}

    static String type(Object raw) throws ActionException {
        if (!(raw instanceof String) || !TYPES.contains(raw)) throw invalid("Unsupported memory value type");
        return (String) raw;
    }

    static int size(String type) throws ActionException {
        return switch (type) {
            case "int32", "uint32", "float32" -> 4;
            case "int64", "uint64", "float64" -> 8;
            default -> throw invalid("A scalar type is required");
        };
    }

    static byte[] encode(String type, Object raw) throws ActionException {
        if (type.equals("bytes")) {
            if (!(raw instanceof String)) throw invalid("Byte values must be a hexadecimal string");
            byte[] result = unhex((String) raw);
            if (result.length < 1 || result.length > 256) throw invalid("Byte patterns must contain 1 to 256 bytes");
            return result;
        }
        String value;
        if (raw instanceof String) value = (String) raw;
        else if (raw instanceof Number && !type.endsWith("64")) value = raw.toString();
        else throw invalid("Memory values must use a decimal string; 64-bit values require strings");
        if (value.isBlank() || value.length() > 128 || !value.equals(value.trim())) throw invalid("Invalid memory value");
        ByteBuffer buffer = ByteBuffer.allocate(size(type)).order(ByteOrder.LITTLE_ENDIAN);
        try {
            if (type.startsWith("int") || type.startsWith("uint")) {
                if (!value.matches("-?[0-9]+")) throw invalid("Integer values must be decimal integers");
                BigInteger n = new BigInteger(value);
                int bits = size(type) * 8;
                boolean signed = type.startsWith("int");
                BigInteger min = signed ? BigInteger.ONE.shiftLeft(bits - 1).negate() : BigInteger.ZERO;
                BigInteger max = BigInteger.ONE.shiftLeft(signed ? bits - 1 : bits).subtract(BigInteger.ONE);
                if (n.compareTo(min) < 0 || n.compareTo(max) > 0) throw invalid("Integer value is outside the target type range");
                if (bits == 32) buffer.putInt(n.intValue()); else buffer.putLong(n.longValue());
            } else {
                // BigDecimal rejects NaN, Infinity, hexadecimal float syntax and trailing junk.
                BigDecimal n = new BigDecimal(value);
                if (type.equals("float32")) {
                    float f = Float.parseFloat(value);
                    if (!Float.isFinite(f)) throw invalid("Floating value must be finite in the target type");
                    buffer.putFloat(f);
                } else {
                    double d = Double.parseDouble(value);
                    if (!Double.isFinite(d)) throw invalid("Floating value must be finite in the target type");
                    buffer.putDouble(d);
                }
            }
            return buffer.array();
        } catch (NumberFormatException malformed) { throw invalid("Invalid decimal memory value"); }
    }

    static String decode(String type, byte[] data, int offset) throws ActionException {
        if (type.equals("bytes")) return hex(data);
        int size = size(type);
        if (offset < 0 || offset + size > data.length) throw invalid("Incomplete memory scalar");
        ByteBuffer buffer = ByteBuffer.wrap(data, offset, size).order(ByteOrder.LITTLE_ENDIAN);
        return switch (type) {
            case "int32" -> Integer.toString(buffer.getInt());
            case "uint32" -> Long.toString(Integer.toUnsignedLong(buffer.getInt()));
            case "int64" -> Long.toString(buffer.getLong());
            case "uint64" -> Long.toUnsignedString(buffer.getLong());
            case "float32" -> Float.toString(buffer.getFloat());
            case "float64" -> Double.toString(buffer.getDouble());
            default -> throw invalid("Unsupported scalar type");
        };
    }

    static long address(Object raw) throws ActionException {
        if (!(raw instanceof String) || !((String) raw).matches("0x[0-9a-fA-F]{1,16}"))
            throw invalid("Memory addresses and offsets must be hexadecimal strings");
        try {
            long n = Long.parseUnsignedLong(((String) raw).substring(2), 16);
            if (n < 0) throw invalid("Address is outside the supported userspace range");
            return n;
        } catch (NumberFormatException bad) { throw invalid("Invalid address"); }
    }

    static String address(long value) { return "0x" + Long.toHexString(value); }

    static String hex(byte[] bytes) {
        char[] chars = new char[bytes.length * 2];
        final char[] alphabet = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            chars[i * 2] = alphabet[(bytes[i] >>> 4) & 15];
            chars[i * 2 + 1] = alphabet[bytes[i] & 15];
        }
        return new String(chars);
    }

    static byte[] unhex(String hex) throws ActionException {
        if (hex == null || hex.length() > 1048576 || (hex.length() & 1) != 0 || !hex.matches("[0-9a-fA-F]*"))
            throw invalid("Invalid hexadecimal bytes");
        byte[] result = new byte[hex.length() / 2];
        for (int i = 0; i < result.length; i++) result[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        return result;
    }

    static ActionException invalid(String message) { return new ActionException("invalid_argument", message); }
}
