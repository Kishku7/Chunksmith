package com.kishku7.chunksmith.command;

import com.kishku7.chunksmith.platform.Sender;

import java.util.List;

public interface ChunkyCommand {
    void execute(Sender sender, CommandArguments arguments);

    List<String> suggestions(final CommandArguments arguments);
}
