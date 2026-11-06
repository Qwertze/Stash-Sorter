package com.porter.qwertestash.manager;

import com.aquarius.command.api.CommandContext;
import com.aquarius.command.api.CommandSource;
import com.aquarius.discord.Embed;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * The source of a command the dashboard runs ("Dashboard"; the owner may run anything, like the terminal). It records what
 * the command says: embeds and lines logged while it runs are collected into its output, and ones logged after it has
 * returned (a command that goes on in the background) are handed to {@code late} as further output for the same job.
 * Output is turned into the protocol's {@code {title, description, color, fields, lines}} here.
 */
public final class DashboardCommandSource implements CommandSource {
    /** The most output kept for a command, in characters. */
    public static final int MAX_OUTPUT = 64 * 1024;

    private static final Pattern ESCAPES = Pattern.compile("\\\\([*_~`>|\\\\\\[\\]()])");

    private final Consumer<JsonObject> late;
    private final List<JsonObject> logged = new ArrayList<>();
    private final List<String> lines = new ArrayList<>();
    private volatile boolean finished;

    /** @param late gets the output of anything logged after {@link #finish()}, already shaped as an output object */
    public DashboardCommandSource(Consumer<JsonObject> late) {
        this.late = late;
    }

    @Override
    public String name() {
        return "Dashboard";
    }

    @Override
    public boolean validateAccountOwner(CommandContext ctx) {
        return true;
    }

    // No resolveSubject override: like the terminal and Discord, the dashboard gets the proxy's fully trusted subject, so
    // its console runs every command the terminal can (the owner turned it on in the terminal with `stash manager
    // dashboard console on`)

    @Override
    public void logEmbed(CommandContext ctx, Embed embed) {
        if (embed == null) return;
        JsonObject out = output(embed, List.of());
        if (finished) deliver(out);
        else synchronized (this) {
            logged.add(out);
        }
    }

    @Override
    public void logMultiLine(List<String> multiLine) {
        if (multiLine == null || multiLine.isEmpty()) return;
        JsonObject out = output(null, multiLine);
        if (finished) deliver(out);
        else synchronized (this) {
            lines.addAll(multiLine);
        }
    }

    private void deliver(JsonObject out) {
        try {
            late.accept(out);
        } catch (RuntimeException ignored) {
            // Output nobody can be given is dropped
        }
    }

    /** The command has returned: from now on what it logs goes to {@code late}. */
    public void finish() {
        finished = true;
    }

    /** Embeds and lines logged while the command ran, as one list of lines to put before its own output. */
    public synchronized List<String> loggedLines() {
        List<String> out = new ArrayList<>();
        for (JsonObject o : logged) {
            if (o.has("title")) out.add(o.get("title").getAsString());
            if (o.has("description")) out.add(o.get("description").getAsString());
            for (var f : o.getAsJsonArray("fields")) out.add(f.getAsJsonObject().get("name").getAsString() + ": " + f.getAsJsonObject().get("value").getAsString());
            for (var l : o.getAsJsonArray("lines")) out.add(l.getAsString());
        }
        out.addAll(lines);
        return out;
    }

    /** An embed (may be null) and extra lines in the shape the server stores. */
    public static JsonObject output(@Nullable Embed embed, List<String> extraLines) {
        JsonObject o = new JsonObject();
        JsonArray fields = new JsonArray();
        JsonArray out = new JsonArray();
        if (embed != null) {
            if (embed.title() != null) o.addProperty("title", clean(embed.title()));
            if (embed.description() != null) o.addProperty("description", clean(embed.description()));
            String color = color(embed);
            if (color != null) o.addProperty("color", color);
            if (embed.fields() != null) {
                for (Embed.Field f : embed.fields()) {
                    JsonObject jf = new JsonObject();
                    jf.addProperty("name", clean(f.name()));
                    jf.addProperty("value", clean(f.value()));
                    fields.add(jf);
                }
            }
        }
        for (String line : extraLines) out.add(clean(line));
        o.add("fields", fields);
        o.add("lines", out);
        return o;
    }

    /** Whether the embed is in the error colour. */
    public static boolean isError(@Nullable Embed embed) {
        return "error".equals(embed == null ? null : color(embed));
    }

    private static @Nullable String color(Embed embed) {
        try {
            var c = embed.color();
            if (c == null) return null;
            var theme = com.aquarius.Globals.CONFIG.theme;
            if (c.equals(theme.error.color())) return "error";
            if (c.equals(theme.success.color())) return "success";
            if (c.equals(theme.primary.color())) return "primary";
            if (c.equals(theme.inQueue.color())) return "queue";
            return String.format("#%02x%02x%02x", c.r(), c.g(), c.b());
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** Discord's backslash escapes removed; the rest of the markdown (bold, code) is left for the page to show. */
    static String clean(@Nullable String text) {
        return text == null ? "" : ESCAPES.matcher(text).replaceAll("$1");
    }

    /** Cuts a list of lines to {@link #MAX_OUTPUT} characters in all, saying so at the end. */
    public static List<String> cap(List<String> in) {
        List<String> out = new ArrayList<>();
        int total = 0;
        for (String line : in) {
            if (total + line.length() > MAX_OUTPUT) {
                out.add("... (output cut at 64 KiB)");
                return out;
            }
            total += line.length() + 1;
            out.add(line);
        }
        return out;
    }
}
