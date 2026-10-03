// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.buildlogic;

import groovy.json.JsonOutput;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;
import org.gradle.build.event.BuildEventsListenerRegistry;
import org.gradle.tooling.events.FinishEvent;
import org.gradle.tooling.events.OperationCompletionListener;
import org.gradle.tooling.events.task.TaskFailureResult;
import org.gradle.tooling.events.task.TaskFinishEvent;
import org.gradle.tooling.events.task.TaskSkippedResult;
import org.gradle.tooling.events.task.TaskSuccessResult;

/** Wall-clock task spans only: no sampling or instrumentation of task code. */
public abstract class BuildTrace implements BuildService<BuildTrace.Parameters>,
        OperationCompletionListener, AutoCloseable {
    public interface Parameters extends BuildServiceParameters {
        RegularFileProperty getOutputFile();
    }

    public abstract static class Registration {
        @Inject public abstract BuildEventsListenerRegistry getEvents();
    }

    private final List<Map<String, Object>> events = new ArrayList<>();

    @Override public void onFinish(FinishEvent event) {
        if (!(event instanceof TaskFinishEvent task)) return;
        var result = task.getResult();
        String outcome = "EXECUTED";
        if (result instanceof TaskFailureResult) outcome = "FAILED";
        else if (result instanceof TaskSkippedResult skipped) outcome = skipped.getSkipMessage();
        else if (result instanceof TaskSuccessResult success) {
            if (success.isFromCache()) outcome = "FROM-CACHE";
            else if (success.isUpToDate()) outcome = "UP-TO-DATE";
        }
        var span = new LinkedHashMap<String, Object>();
        span.put("name", task.getDescriptor().getTaskPath());
        span.put("cat", "Gradle task");
        span.put("ph", "X");
        span.put("pid", 3);
        span.put("ts", result.getStartTime() * 1000);
        span.put("dur", (result.getEndTime() - result.getStartTime()) * 1000);
        span.put("args", Map.of("outcome", outcome));
        events.add(span);
    }

    @Override public void close() throws IOException {
        events.sort(Comparator.comparingLong(event -> (Long) event.get("ts")));
        // Completion callbacks do not identify execution threads. Display parallel
        // intervals on nonoverlapping task lanes, without claiming thread identity.
        var ends = new ArrayList<Long>();
        for (var event : events) {
            long start = (Long) event.get("ts");
            int lane = 0;
            while (lane < ends.size() && ends.get(lane) > start) lane++;
            if (lane == ends.size()) ends.add(0L);
            ends.set(lane, start + (Long) event.get("dur"));
            event.put("tid", lane + 1);
        }
        events.add(Map.of("name", "process_name", "ph", "M", "pid", 3,
                "tid", 0, "args", Map.of("name", "Gradle tasks")));
        var output = getParameters().getOutputFile().get().getAsFile().toPath();
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, JsonOutput.toJson(Map.of("traceEvents", events, "displayTimeUnit", "ms")) + "\n");
    }
}
