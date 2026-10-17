package com.netgician.rplauncher.mms;

/** The one helper the MMS codec used from android.text.TextUtils. */
public final class TextUtils {
    private TextUtils() {}
    public static boolean isEmpty(CharSequence s) { return s == null || s.length() == 0; }
}
