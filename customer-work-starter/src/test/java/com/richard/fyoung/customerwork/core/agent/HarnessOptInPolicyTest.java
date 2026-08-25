package com.richard.fyoung.customerwork.core.agent;

import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HarnessOptInPolicyTest {

    @Test
    void apply_shouldDisableEveryUnconfiguredHarnessDefault() {
        HarnessAgent.Builder builder = mock(HarnessAgent.Builder.class);

        HarnessOptInPolicy.apply(builder, false, false, false, false, false, false);

        verify(builder).disableFilesystemTools();
        verify(builder).disableShellTool();
        verify(builder).disableCompaction();
        verify(builder).disableToolResultEviction();
        verify(builder).disableSubagents();
        verify(builder).disableDynamicSkills();
        verify(builder, never()).disableDynamicSubagents();
    }

    @Test
    void apply_shouldKeepConfiguredFeatures_andDisableOnlyDynamicSubagents() {
        HarnessAgent.Builder builder = mock(HarnessAgent.Builder.class);

        HarnessOptInPolicy.apply(builder, true, true, true, true, false, true);

        verify(builder).disableDynamicSubagents();
        verify(builder, never()).disableFilesystemTools();
        verify(builder, never()).disableShellTool();
        verify(builder, never()).disableCompaction();
        verify(builder, never()).disableToolResultEviction();
        verify(builder, never()).disableSubagents();
        verify(builder, never()).disableDynamicSkills();
    }

    @Test
    void pruneBuiltInTools_shouldRemoveWaitTool_onlyWhenSubagentsAreDisabled() {
        HarnessAgent agent = mock(HarnessAgent.class);
        Toolkit toolkit = mock(Toolkit.class);
        when(agent.getToolkit()).thenReturn(toolkit);

        HarnessOptInPolicy.pruneBuiltInTools(agent, false, false);

        verify(toolkit).removeTool("wait_async_results");
    }
}
