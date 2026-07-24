package com.affiliateo.elements

/**
 * The embeddable Affiliateo elements. Each renders one affiliate's own data for
 * one app.
 *
 * BALANCE, WITHDRAW and IDENTITY confirm the affiliate's identity in a window
 * on affiliateo.com before rendering, because an Affiliateo wallet is per person
 * and spans every program they are in. The view hosts that window for you.
 */
enum class AffiliateoComponent(val slug: String) {
    /** Referral link with clicks, sales and total earned. The combined piece. */
    AFFILIATE("affiliate"),

    /** Just the referral link, with a dropdown to switch link formats. */
    LINK("link"),

    /** A scannable QR code of the referral link. */
    QR("qr"),

    /** Just the clicks, sales and total earned. */
    STATS("stats"),

    /** Your catalogue with what they earn on each product. */
    PRODUCTS("products"),

    /** Recent sales; refunds show as negatives. */
    ACTIVITY("activity"),

    /** Wallet: ready to withdraw, pending, settling. */
    BALANCE("balance"),

    /** The balance plus a button that opens the real cash-out flow. */
    WITHDRAW("withdraw"),

    /** Gets them verified and set up to be paid. */
    IDENTITY("identity"),
}
