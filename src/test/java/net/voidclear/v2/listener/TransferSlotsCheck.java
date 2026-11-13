package net.voidclear.v2.listener;

public final class TransferSlotsCheck {
    public static void main(String[] args) {
        var seen = new boolean[36];
        for (int raw = 54; raw < 90; raw++) {
            int slot = TrashListener.storageSlot(raw);
            assert slot >= 0 && slot < 36 && !seen[slot];
            seen[slot] = true;
        }
        assert TrashListener.storageSlot(54) == 9;
        assert TrashListener.storageSlot(80) == 35;
        assert TrashListener.storageSlot(81) == 0;
        assert TrashListener.storageSlot(89) == 8;
        System.out.println("TransferSlotsCheck: ALL OK");
    }
}
