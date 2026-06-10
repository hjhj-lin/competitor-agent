package com.competitor.agent.framework;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentPipelineTest {

    private AgentPipeline pipeline;

    // 简单的Mock Agent
    private static class StubAgent implements Agent {
        private final String name;
        private final AgentResult result;

        StubAgent(String name, AgentResult result) {
            this.name = name;
            this.result = result;
        }

        @Override
        public String getName() { return name; }

        @Override
        public String getDescription() { return name + " description"; }

        @Override
        public AgentResult execute(AgentContext context) { return result; }
    }

    @BeforeEach
    void setUp() {
        pipeline = new AgentPipeline();
    }

    @Test
    void execute_allSuccess_returnsSuccess() {
        Agent agent1 = new StubAgent("collect", AgentResult.success(Map.of()));
        Agent agent2 = new StubAgent("analyze", AgentResult.success(Map.of()));

        pipeline.add(agent1, 0, false);
        pipeline.add(agent2, 0, false);

        AgentContext context = AgentContext.of(1L, "华为");
        PipelineResult result = pipeline.execute(context);

        assertTrue(result.isSuccess());
        assertFalse(result.isDegraded());
        assertEquals(2, result.getAgentResults().size());
    }

    @Test
    void execute_skipOnFailure_returnsDegraded() {
        Agent agent1 = new StubAgent("collect", AgentResult.success(Map.of()));
        Agent agent2 = new StubAgent("review", AgentResult.fail("审核失败"));

        pipeline.add(agent1, 0, false);
        pipeline.add(agent2, 0, true); // skipOnFailure=true

        AgentContext context = AgentContext.of(1L, "华为");
        PipelineResult result = pipeline.execute(context);

        assertTrue(result.isSuccess());
        assertTrue(result.isDegraded());
        assertNotNull(result.getDegradeInfo());
    }

    @Test
    void execute_noSkipOnFailure_returnsFail() {
        Agent agent1 = new StubAgent("collect", AgentResult.success(Map.of()));
        Agent agent2 = new StubAgent("analyze", AgentResult.fail("分析失败"));

        pipeline.add(agent1, 0, false);
        pipeline.add(agent2, 0, false); // skipOnFailure=false

        AgentContext context = AgentContext.of(1L, "华为");
        PipelineResult result = pipeline.execute(context);

        assertFalse(result.isSuccess());
        assertEquals("analyze", result.getFailedAgent());
    }

    @Test
    void execute_firstAgentFails_stopsPipeline() {
        Agent agent1 = new StubAgent("collect", AgentResult.fail("采集失败"));
        Agent agent2 = new StubAgent("analyze", AgentResult.success(Map.of()));

        pipeline.add(agent1, 0, false);
        pipeline.add(agent2, 0, false);

        AgentContext context = AgentContext.of(1L, "华为");
        PipelineResult result = pipeline.execute(context);

        assertFalse(result.isSuccess());
        assertEquals("collect", result.getFailedAgent());
        assertEquals(1, result.getAgentResults().size()); // 第二个agent未执行
    }

    @Test
    void execute_outputsPassedBetweenAgents() {
        Agent agent1 = new StubAgent("collect", AgentResult.success(Map.of("data", "采集结果")));
        Agent agent2 = new Agent() {
            @Override public String getName() { return "analyze"; }
            @Override public String getDescription() { return "analyze"; }
            @Override
            public AgentResult execute(AgentContext context) {
                Object data = context.getOutputs().get("collect");
                return AgentResult.success(Map.of("received", data != null));
            }
        };

        pipeline.add(agent1, 0, false);
        pipeline.add(agent2, 0, false);

        AgentContext context = AgentContext.of(1L, "华为");
        PipelineResult result = pipeline.execute(context);

        assertTrue(result.isSuccess());
    }

    @Test
    void execute_withListener_callbacksInvoked() {
        StringBuilder log = new StringBuilder();
        Agent agent = new StubAgent("collect", AgentResult.success(Map.of()));

        pipeline.add(agent, 0, false);
        pipeline.listener(new PipelineListener() {
            @Override public void onAgentStart(Long taskId, String agentName) {
                log.append("start:").append(agentName).append(";");
            }
            @Override public void onAgentComplete(Long taskId, String agentName, boolean success) {
                log.append("complete:").append(agentName).append(";");
            }
            @Override public void onPipelineComplete(Long taskId, PipelineResult result) {
                log.append("pipeline:done");
            }
        });

        AgentContext context = AgentContext.of(1L, "华为");
        pipeline.execute(context);

        String logStr = log.toString();
        assertTrue(logStr.contains("start:collect"));
        assertTrue(logStr.contains("complete:collect"));
        assertTrue(logStr.contains("pipeline:done"));
    }
}
