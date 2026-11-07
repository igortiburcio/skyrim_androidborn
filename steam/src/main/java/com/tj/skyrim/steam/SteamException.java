/* Part of the SteamKit2 port used by Skyrim Androidborn.
 * SteamKit2 is LGPL-2.1; see ../../LICENSE-LGPL-2.1.txt. */
package com.tj.skyrim.steam;

/** A Steam network or protocol error. {@link #eresult()} is 0 when unknown. */
public class SteamException extends Exception {
    private final int eresult;

    public SteamException(String message) {
        this(message, 0);
    }

    public SteamException(String message, int eresult) {
        super(eresult == 0 ? message : message + " (EResult " + eresult + ")");
        this.eresult = eresult;
    }

    public SteamException(String message, Throwable cause) {
        super(message, cause);
        this.eresult = 0;
    }

    /** The Steam EResult, or 0 when the failure was local. */
    public int eresult() {
        return eresult;
    }
}
