package com.buddyapps.aod;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Tiny rolling log shown in the app, so problems are visible without a computer. */
public class Diag {

    private static final String FILE = "aod-log.txt";
    private static final int MAX_LINES = 60;

    static void log(Context context, String text) {
        try {
            String line = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date())
                    + "  " + text + "\n";
            File file = new File(context.getFilesDir(), FILE);
            String old = "";
            if (file.exists()) {
                java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.FileReader(file));
                StringBuilder sb = new StringBuilder();
                String l;
                while ((l = r.readLine()) != null) {
                    sb.append(l).append('\n');
                }
                r.close();
                old = sb.toString();
            }
            String all = old + line;
            String[] lines = all.split("\n");
            if (lines.length > MAX_LINES) {
                StringBuilder sb = new StringBuilder();
                for (int i = lines.length - MAX_LINES; i < lines.length; i++) {
                    sb.append(lines[i]).append('\n');
                }
                all = sb.toString();
            }
            FileOutputStream out = new FileOutputStream(file);
            out.write(all.getBytes("UTF-8"));
            out.close();
        } catch (Exception ignored) {
        }
    }

    static String tail(Context context, int count) {
        try {
            File file = new File(context.getFilesDir(), FILE);
            if (!file.exists()) return "нет событий";
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(file));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) sb.append(l).append('\n');
            r.close();
            String[] lines = sb.toString().split("\n");
            StringBuilder out = new StringBuilder();
            for (int i = Math.max(0, lines.length - count); i < lines.length; i++) {
                out.append(lines[i]).append('\n');
            }
            return out.toString().trim();
        } catch (Exception e) {
            return "нет событий";
        }
    }
}
