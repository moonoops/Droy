package io.droyagent.androiduse;

import java.util.UUID;

final class BluetoothRules {
    private BluetoothRules() {}

    static UUID uuid(Object value) {
        if (!(value instanceof String text)
                || !text.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new IllegalArgumentException("uuid");
        return UUID.fromString(text);
    }

    static int writeType(String value) {
        return switch (value) {
            case "with_response" -> 2;
            case "without_response" -> 1;
            case "signed" -> 4;
            default -> throw new IllegalArgumentException("writeType");
        };
    }

    static void writeLength(int length, int mtu) { writeLength(length, mtu, 2); }

    static void writeLength(int length, int mtu, int writeType) {
        // Splitting an ATT payload can change the device's application protocol.
        int overhead = writeType == 4 ? 15 : 3;
        if (length < 0 || length > Math.min(512, mtu - overhead)) throw new IllegalArgumentException("payload_length");
    }

    static String hex(byte[] value) {
        StringBuilder text = new StringBuilder(value.length * 2);
        for (byte item : value) text.append(Character.forDigit((item >>> 4) & 15, 16))
                .append(Character.forDigit(item & 15, 16));
        return text.toString();
    }
}
