package com.skyblock.sales;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class SalesReceiverManager {

    private static final Set<SalesReceiverBlockEntity> activeReceivers = new HashSet<>();

    public static void registerReceiver(SalesReceiverBlockEntity receiver) {
        activeReceivers.add(receiver);
    }

    public static void unregisterReceiver(SalesReceiverBlockEntity receiver) {
        activeReceivers.remove(receiver);
    }

    public static void updateAllReceivers(Map<String, Long> newInventory) {
        for (SalesReceiverBlockEntity receiver : activeReceivers) {
            receiver.updateRemoteInventory(newInventory);
        }
    }
}
