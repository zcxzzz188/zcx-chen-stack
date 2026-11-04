package com.zcx.chenstack.service;

import com.zcx.chenstack.domain.vo.CustomerServiceEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class CustomerServiceToolsTest {
    @Test void negativePublishedPageDescriptionDoesNotPromiseAReplyButton() {
        String description = "这里没有添加已有文章按钮。";
        assertFalse(CustomerServiceTools.promisesNavigation(description));
        List<CustomerServiceEvent> events = new ArrayList<>();
        var tools = new CustomerServiceTools(7, mock(EditableArticleService.class), events::add);
        assertDoesNotThrow(() -> tools.ensureReplyActions(description));
        tools.publishPending();
        assertTrue(events.isEmpty());
    }

    @Test void pageButtonInstructionsDoNotPromiseChatNavigation() {
        for (String instruction : List.of("点击“编辑”按钮打开表单。", "在文章管理页，点击“编辑”按钮进入编辑器。")) {
            assertFalse(CustomerServiceTools.promisesNavigation(instruction), instruction);
            List<CustomerServiceEvent> events = new ArrayList<>();
            var tools = new CustomerServiceTools(7, mock(EditableArticleService.class), events::add);
            assertDoesNotThrow(() -> tools.ensureReplyActions(instruction));
            tools.publishPending();
            assertTrue(events.isEmpty());
        }
    }

    @Test void explicitReplyPromisesStillRejectUnknownButtons() {
        for (String promise : List.of("下方有支付中心按钮。", "下边有支付中心按钮。", "下面有支付中心按钮。",
                "本条回复有支付中心按钮。", "这条回复有支付中心按钮。", "本条回答有支付中心按钮。",
                "当前回复有支付中心按钮。", "当前回答有支付中心按钮。", "已准备支付中心按钮。",
                "已提供支付中心按钮。", "已展示支付中心按钮。", "已显示支付中心按钮。",
                "为你提供支付中心按钮。", "给你准备支付中心按钮。", "这里有支付中心按钮。", "这儿有支付中心按钮。")) {
            assertTrue(CustomerServiceTools.promisesNavigation(promise), promise);
            List<CustomerServiceEvent> events = new ArrayList<>();
            var tools = new CustomerServiceTools(7, mock(EditableArticleService.class), events::add);
            assertEquals("客服导航入口未确认", assertThrows(IllegalStateException.class, () -> tools.ensureReplyActions(promise)).getMessage());
            assertTrue(events.isEmpty());
        }
    }

    @Test void negativeDescriptionsCannotHideASeparatePromiseOrAButtonAttribute() {
        for (String promise : List.of("这里没有添加已有文章按钮，但下方有支付中心按钮。",
                "下方没有按钮，本条回复有支付中心按钮。", "下方有不显示余额的支付中心按钮。")) {
            assertTrue(CustomerServiceTools.promisesNavigation(promise), promise);
            var tools = new CustomerServiceTools(7, mock(EditableArticleService.class), event -> fail("未经核验不能发布"));
            assertThrows(IllegalStateException.class, () -> tools.ensureReplyActions(promise));
        }
    }

    @Test void knownReplyPromisesStillPrepareOnlyTheirWhitelistedActions() {
        for (String promise : List.of("下方按钮可进入文章管理。", "本条回复已准备文章管理按钮。", "为你提供文章管理按钮。")) {
            List<CustomerServiceEvent> events = new ArrayList<>();
            var tools = new CustomerServiceTools(7, mock(EditableArticleService.class), events::add);
            tools.ensureReplyActions(promise); tools.ensureReplyActions(promise); tools.publishPending();
            assertEquals(List.of("OPEN_ARTICLE_MANAGE"), events.stream().map(CustomerServiceEvent::action).toList());
        }
    }
}
