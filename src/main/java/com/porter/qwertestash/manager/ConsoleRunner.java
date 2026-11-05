package com.porter.qwertestash.manager;

import com.aquarius.command.api.CommandContext;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static com.aquarius.Globals.COMMAND;
import static com.porter.qwertestash.QwerteStashPlugin.LOG;

/**
 * Runs a command for the dashboard the way the proxy's own HTTP command handler does: a CommandContext on a
 * {@link DashboardCommandSource}, executed by COMMAND, then the embed and the multi-line output read from the context.
 * Call it from a worker thread, never the bot tick. Input the command marks sensitive is not logged, and only its title
 * is sent back.
 */
public final class ConsoleRunner {
    private ConsoleRunner() {
    }

    /**
     * @param ok        the command ran and its embed is not in the error colour
     * @param sensitive the input was marked sensitive: it is not logged and the page hides it
     * @param output    {title, description, color, fields, lines}
     * @param error     why it did not run at all, or null
     */
    public record Outcome(boolean ok, boolean sensitive, JsonObject output, @Nullable String error) {
    }

    /** Runs a command without the console gate: for the commands a settings change maps to. */
    public static Outcome run(String command, Consumer<JsonObject> late) {
        return run(command, false, late);
    }

    /**
     * @param gate refuse the commands the dashboard console may not run (see {@link CommandGate})
     */
    public static Outcome run(String command, boolean gate, Consumer<JsonObject> late) {
        if (gate) {
            String refused;
            try {
                refused = CommandGate.check(command, COMMAND);
            } catch (RuntimeException | LinkageError e) {
                refused = "the command could not be checked";
            }
            if (refused != null) {
                LOG.warn("Qwerte Stash: a dashboard command was refused: {}", refused);
                JsonObject out = DashboardCommandSource.output(null, List.of("Refused: " + refused + "."));
                return new Outcome(false, false, out, refused);
            }
        }
        DashboardCommandSource source = new DashboardCommandSource(late);
        CommandContext ctx;
        String failure = null;
        try {
            ctx = CommandContext.create(command, source);
            COMMAND.execute(ctx);
        } catch (RuntimeException | LinkageError e) {
            source.finish();
            LOG.warn("Qwerte Stash: a dashboard command failed: {}", e.toString());
            JsonObject out = DashboardCommandSource.output(null, List.of("The command failed: " + e));
            return new Outcome(false, false, out, e.toString());
        }
        source.finish();

        boolean sensitive = ctx.isSensitiveInput();
        if (!sensitive) LOG.info("Dashboard command: {}", command);

        var embed = ctx.getEmbed();
        boolean error = DashboardCommandSource.isError(embed);
        if (sensitive) {
            JsonObject out = new JsonObject();
            if (embed.title() != null) out.addProperty("title", DashboardCommandSource.clean(embed.title()));
            out.add("fields", new JsonArray());
            out.add("lines", new JsonArray());
            return new Outcome(!error, true, out, null);
        }

        List<String> lines = new ArrayList<>(source.loggedLines());
        List<String> multi = ctx.getMultiLineOutput();
        if (multi != null) lines.addAll(multi);
        JsonObject out = DashboardCommandSource.output(embed, DashboardCommandSource.cap(lines));
        if (embed.title() == null && embed.description() == null && out.getAsJsonArray("fields").isEmpty() && out.getAsJsonArray("lines").isEmpty()) {
            out.getAsJsonArray("lines").add("ok");
        }
        capFields(out);
        return new Outcome(!error, false, out, failure);
    }

    /** Fields past the output limit are dropped. */
    private static void capFields(JsonObject out) {
        JsonArray fields = out.getAsJsonArray("fields");
        int total = 0;
        JsonArray kept = new JsonArray();
        for (var f : fields) {
            JsonObject o = f.getAsJsonObject();
            total += o.get("name").getAsString().length() + o.get("value").getAsString().length();
            if (total > DashboardCommandSource.MAX_OUTPUT) break;
            kept.add(o);
        }
        out.add("fields", kept);
    }

    /** The error text of an output, for a failed settings change. */
    public static String text(JsonObject output) {
        StringBuilder sb = new StringBuilder();
        if (output.has("title")) sb.append(output.get("title").getAsString());
        if (output.has("description")) sb.append(sb.length() > 0 ? ": " : "").append(output.get("description").getAsString());
        for (var f : output.getAsJsonArray("fields")) {
            sb.append(sb.length() > 0 ? "; " : "").append(f.getAsJsonObject().get("name").getAsString()).append(": ").append(f.getAsJsonObject().get("value").getAsString());
        }
        for (var l : output.getAsJsonArray("lines")) sb.append(sb.length() > 0 ? "; " : "").append(l.getAsString());
        String s = sb.toString();
        return s.length() > 300 ? s.substring(0, 299) + "…" : s;
    }
}
