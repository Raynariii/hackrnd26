package io.blueprints.runner;

import io.blueprints.runner.RunModels.RunBatchResult;
import io.blueprints.runner.RunModels.RunResult;
import io.blueprints.runner.RunModels.RunnerBatchRequest;
import io.blueprints.runner.RunModels.RunnerRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
public class RunController {

    private final JavaSandbox sandbox;

    public RunController(JavaSandbox sandbox) {
        this.sandbox = sandbox;
    }

    @PostMapping("/internal/run")
    public RunResult run(@RequestBody RunnerRequest request) throws IOException, InterruptedException {
        return sandbox.run(request);
    }

    @PostMapping("/internal/run-batch")
    public RunBatchResult runBatch(@RequestBody RunnerBatchRequest request) throws IOException, InterruptedException {
        return sandbox.runBatch(request);
    }
}
