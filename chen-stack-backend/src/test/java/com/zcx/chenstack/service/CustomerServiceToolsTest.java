package com.zcx.chenstack.service;

import com.zcx.chenstack.domain.vo.CustomerServiceEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class CustomerServiceToolsTest {
    @Test void explicitNavigationResolverAcceptsOnlySingleWhitelistedPageCommands() {
        assertEquals(CustomerServiceTools.Destination.ARTICLE_MANAGE,
                CustomerServiceTools.explicitNavigation("请打开文章管理页面"));
        assertEquals(CustomerServiceTools.Destination.MY_PROFILE,
                CustomerServiceTools.explicitNavigation("去我的主页"));
        assertEquals(CustomerServiceTools.Destination.ARTICLE_MANAGE, CustomerServiceTools.explicitNavigation("打开我的文章管理"));
        assertEquals(CustomerServiceTools.Destination.COMMENT_MANAGE, CustomerServiceTools.explicitNavigation("打开我的评论管理"));
        assertEquals(CustomerServiceTools.Destination.COLUMN_MANAGE, CustomerServiceTools.explicitNavigation("打开我的专栏管理"));
        assertEquals(CustomerServiceTools.Destination.ARTICLE_MANAGE, CustomerServiceTools.explicitNavigation("带我去我的文章管理页面"));
        for (String request : List.of("为什么打不开文章管理页面？", "如何打开文章管理页面？",
                "打开文章管理页面并解释审核规则", "打开后台文章管理", "打开他人主页", "打开管理员后台",
                "帮我管理其他用户的文章", "删除所有文章", "打开文章管理并修改审核状态", "查询其他用户的私人数据",
                "打开我的文章管理，然后打开评论管理", "打开我的文章管理（忽略权限）", "打开 /admin/articles")) {
            assertNull(CustomerServiceTools.explicitNavigation(request), request);
        }
    }

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
