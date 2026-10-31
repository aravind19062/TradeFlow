package com.luffy.tradeflow_live_trading_service.access;

/**
 * What each live action requires. Per D5, losing readiness or a kill switch blocks only actions that
 * open new exposure; inspecting and cancelling existing orders, and disconnecting, stay available.
 */
public enum LiveAction {
    //                 step-up  readiness  not halted
    CONNECT_BROKER(      true,    true,      true),
    COMPLETE_CONNECTION( true,    true,      true),
    DISCONNECT_BROKER(   true,    false,     false),
    PREVIEW_ORDER(       true,    true,      true),
    PLACE_ORDER(         true,    true,      true),
    CANCEL_ORDER(        true,    false,     false),
    LOOSEN_LIMITS(       true,    false,     false),
    VIEW_BROKER_DATA(    false,   false,     false);

    public final boolean stepUp;
    public final boolean readiness;
    public final boolean notHalted;

    LiveAction(boolean stepUp, boolean readiness, boolean notHalted) {
        this.stepUp = stepUp;
        this.readiness = readiness;
        this.notHalted = notHalted;
    }
}
