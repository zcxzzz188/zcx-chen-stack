package com.zcx.chenstack.service;

import com.zcx.chenstack.domain.vo.CustomerServiceEvent;


import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;


import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/** 每请求独立实例；私有结果仅在回答通过校验后发给当前 SSE，不进入模型和聊天历史。 */
public final class CustomerServiceTools {
    private final Integer userId;
    private final EditableArticleService articles;
    private final Consumer<CustomerServiceEvent> events;
    private final ConcurrentLinkedQueue<CustomerServiceEvent> pending = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean queried = new AtomicBoolean();
    private volatile String directReply;

    public CustomerServiceTools(Integer userId, EditableArticleService articles, Consumer<CustomerServiceEvent> events) {
        this.userId = userId;
        this.articles = articles;
        this.events = events;
    }

    @Tool(description = "仅在用户明确要查找或编辑自己创作的文章、草稿时，查询当前登录用户本人可编辑的文章。浏览历史、读过的文章、他人作品和平台操作帮助不属于本人创作查询。仅支持分页，不接受用户ID或文章ID，也不支持按标题、正文、日期筛选。卡片只展示标题、发布/审核状态和编辑按钮，没有写作日期。真实卡片直接展示给用户，模型只收到条数与分页摘要，禁止编造标题、ID或URL。可与知识查询配合，勿查询其他人的私有数据。")
    public String listMyEditableArticles(@ToolParam(description = "页码，1至1000") int page,
                                        @ToolParam(description = "每页条数，1至20") int pageSize) {
        if (!queried.compareAndSet(false, true)) {
            return "本轮已经查询过，请使用随完整回答展示的文章卡片。";
        }
        try {
            CustomerServiceEvent result = articles.listForUser(userId, page, pageSize);
            pending.add(result);
            directReply = "第" + page + "页，本页" + result.articles().size() + "篇，" + (Boolean.TRUE.equals(result.hasMore()) ? "还有下一页。" : "没有下一页。")
                    + "真实卡片已准备，将随完整回答展示，请用户点击目标卡片的编辑按钮，不要索取ID或标题。卡片仅含标题和发布/审核状态，不提供写作日期，也没有按标题、正文或日期筛选的能力。";
            return directReply;
        } catch (RuntimeException error) {
            pending.add(CustomerServiceEvent.state("ERROR_STATE", "暂时无法查询文章，请点击重试。", "RETRY_ARTICLES", null));
            directReply = "文章查询失败，可以点击重试；当前没有可用的查询结果。";
            return directReply;
        }
    }

    public enum Destination {
        LOGIN("去登录"), CREATE_ARTICLE("去写文章"), COLUMN_MANAGE("去管理专栏"),
        SETTINGS("去个人设置"), SEARCH("去搜索"), ARTICLES("去文章广场"),
        MESSAGES("去站内私信"), NOTIFICATIONS("去消息中心"),
        ARTICLE_MANAGE("去管理文章"), COMMENT_MANAGE("去管理评论"), MY_PROFILE("去我的主页");

        private final String text;
        Destination(String text) { this.text = text; }
    }

    @Tool(description = "展示用户端真实页面导航按钮，由用户点击。仅提供入口，不保存、不发布、不删除，不操作后台或代替管理员授权。用户目标有对应入口时直接展示，并按已核验正文说明后续步骤。")
    public String showNavigation(@ToolParam(description = "LOGIN登录，CREATE_ARTICLE新建文章，COLUMN_MANAGE本人专栏管理，SETTINGS个人资料/安全/邮件设置，SEARCH搜索，ARTICLES文章广场，MESSAGES站内私信，NOTIFICATIONS消息中心，ARTICLE_MANAGE本人文章管理，COMMENT_MANAGE本人评论管理，MY_PROFILE本人主页（历史、收藏和关注从主页标签进入）") Destination destination) {
        if (destination == null) return "请明确要打开的页面。";
        String action = "OPEN_" + destination.name();
        String text = destination.text;
        pending.add(CustomerServiceEvent.state("NAVIGATION_ACTION", text, action, "SUPPORTED"));
        return "已准备“" + text + "”按钮，将随完整回答展示，尚未修改任何业务数据。按钮仅提供入口，请仍依据帮助资料说明用户所问的操作步骤、必要字段、前提和审核限制；目标已明确时直接结束，不追加代办或查询提议。";
    }
    public void knowledgeStatus(String status) {
        pending.add(CustomerServiceEvent.state("KNOWLEDGE_STATUS", null, null, status));
    }

    public void publishPending() {
        CustomerServiceEvent event;
        while ((event = pending.poll()) != null) events.accept(event);
    }

    public void discardPending() { pending.clear(); }

    public String directReply() {
        return directReply;
    }
}
