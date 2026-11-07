package com.porter.qwertestash.orders;

import org.jspecify.annotations.Nullable;

/**
 * What the delivery storage can take now (1.9.0): one of its chests has room, a chest that may have been emptied is to be
 * looked at, another chest is to be put up, or nothing can be done but wait for a player to claim an order.
 *
 * @param kind    what to do
 * @param why     for {@link Kind#WAIT}: what is being waited for ("delivery storage full, auto expand is off")
 * @param recheck for {@link Kind#RECHECK}: a block of the chest to look at
 */
public record DeliveryRoom(Kind kind, @Nullable String why, DeliveryBook.@Nullable Pos recheck) {
    public enum Kind { ROOM, RECHECK, EXPAND, WAIT }

    public static final DeliveryRoom ROOM = new DeliveryRoom(Kind.ROOM, null, null);
    public static final DeliveryRoom EXPAND = new DeliveryRoom(Kind.EXPAND, null, null);

    public static DeliveryRoom recheck(DeliveryBook.Pos at) {
        return new DeliveryRoom(Kind.RECHECK, null, at);
    }

    public static DeliveryRoom waitFor(String why) {
        return new DeliveryRoom(Kind.WAIT, why, null);
    }
}
