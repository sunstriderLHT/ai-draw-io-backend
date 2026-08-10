package cn.bugstack.ai.test.domain.agent;

import cn.bugstack.ai.domain.agent.model.valobj.enums.OutputModeEnum;
import org.junit.Assert;
import org.junit.Test;

public class OutputModeEnumTest {

    @Test
    public void shouldParseCanonicalCode() {
        Assert.assertEquals(
                OutputModeEnum.FINAL_WITH_TRACE,
                OutputModeEnum.fromCode("FINAL_WITH_TRACE").orElse(null));
    }

    @Test
    public void shouldParseTrimmedCodeIgnoringCase() {
        Assert.assertEquals(
                OutputModeEnum.FINAL_ONLY,
                OutputModeEnum.fromCode("  final_only  ").orElse(null));
    }

    @Test
    public void shouldRejectMissingOrUnknownCode() {
        Assert.assertFalse(OutputModeEnum.fromCode(null).isPresent());
        Assert.assertFalse(OutputModeEnum.fromCode(" ").isPresent());
        Assert.assertFalse(OutputModeEnum.fromCode("UNKNOWN").isPresent());
    }
}
