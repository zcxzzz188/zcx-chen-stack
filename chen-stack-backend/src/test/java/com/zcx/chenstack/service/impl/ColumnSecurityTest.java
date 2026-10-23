package com.zcx.chenstack.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.zcx.chenstack.config.ChenStackConfig;
import com.zcx.chenstack.domain.dto.ColumnDto;
import com.zcx.chenstack.domain.entity.Article;
import com.zcx.chenstack.domain.entity.ArticleColumn;
import com.zcx.chenstack.domain.entity.Column;
import com.zcx.chenstack.domain.entity.LoginUser;
import com.zcx.chenstack.domain.entity.SysUser;
import com.zcx.chenstack.domain.vo.ColumnVo;
import com.zcx.chenstack.exception.BlogException;
import com.zcx.chenstack.mapper.ArticleColumnMapper;
import com.zcx.chenstack.mapper.ArticleMapper;
import com.zcx.chenstack.mapper.ColumnMapper;
import com.zcx.chenstack.mapper.SysUserMapper;
import com.zcx.chenstack.service.EditableArticleService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.InvocationTargetException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ColumnSecurityTest {
    private final ArticleMapper articles = mock(ArticleMapper.class);
    private final ColumnMapper columns = mock(ColumnMapper.class);
    private final ArticleColumnMapper links = mock(ArticleColumnMapper.class);
    private final SysUserMapper users = mock(SysUserMapper.class);
    private final ArticleServiceImpl articleService = new ArticleServiceImpl();
    private final ColumnServiceImpl columnService = new ColumnServiceImpl();

    @BeforeAll static void metadata() {
        var configuration = new MybatisConfiguration();
        for (Class<?> type : List.of(Article.class, Column.class, ArticleColumn.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "column-test"), type);
        }
    }

    @BeforeEach void setup() {
        login(1);
        ReflectionTestUtils.setField(articleService, "articleMapper", articles);
        ReflectionTestUtils.setField(articleService, "columnMapper", columns);
        ReflectionTestUtils.setField(articleService, "articleColumnMapper", links);
        ReflectionTestUtils.setField(columnService, "articleMapper", articles);
        ReflectionTestUtils.setField(columnService, "columnMapper", columns);
        ReflectionTestUtils.setField(columnService, "articleColumnMapper", links);
        ReflectionTestUtils.setField(columnService, "sysUserMapper", users);
        ReflectionTestUtils.setField(columnService, "chenStackConfig", new ChenStackConfig());
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    private static void login(int user) {
        var account = new SysUser().setId(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new LoginUser(account), null, List.of()));
    }
    private Column column(int owner, int status) {
        return new Column().setId(10).setUserId(owner).setExamineStatus(status).setShowStatus(0)
                .setIsDeleted(0).setArticleCount(99).setName("专栏").setSort(1);
    }
    private Article article(int owner) {
        return new Article().setId(20).setUserId(owner).setIsDeleted(0).setEditStatus(0)
                .setExamineStatus(1).setVisibleRange(0).setTitle("public");
    }
    private void saveLinks(List<Integer> ids) throws Throwable {
        var method = ArticleServiceImpl.class.getDeclaredMethod("saveArticleColumnRelations", Integer.class, List.class);
        method.setAccessible(true);
        try { method.invoke(articleService, 20, ids); }
        catch (InvocationTargetException e) { throw e.getCause(); }
    }

    @Test void associationRequiresBothOwnersAndUsableColumn() throws Throwable {
        when(articles.selectById(20)).thenReturn(article(1));
        when(links.insert(any(ArticleColumn.class))).thenReturn(1);
        for (int status : List.of(0, 1)) {
            when(columns.selectOne(any())).thenReturn(column(1, status));
            saveLinks(List.of(10, 10));
        }
        verify(links, times(2)).insert(any(ArticleColumn.class));
        clearInvocations(links);
        for (Column denied : Arrays.asList(null, column(2, 1), column(1, 2), column(1, 1).setIsDeleted(1))) {
            when(columns.selectOne(any())).thenReturn(denied);
            assertThrows(BlogException.class, () -> saveLinks(List.of(10)));
        }
        verify(links, never()).insert(any(ArticleColumn.class));
    }

    @Test void associationCannotUseForeignDeletedOrAnonymousArticle() {
        when(articles.selectById(20)).thenReturn(article(2), article(1).setIsDeleted(1), article(1));
        assertThrows(BlogException.class, () -> saveLinks(List.of(10)));
        assertThrows(BlogException.class, () -> saveLinks(List.of(10)));
        SecurityContextHolder.clearContext();
        assertThrows(BlogException.class, () -> saveLinks(List.of(10)));
        verifyNoInteractions(columns, links);
    }

    @Test void associationLocksColumnUntilTransactionEnds() throws Throwable {
        when(articles.selectById(20)).thenReturn(article(1));
        when(columns.selectOne(any())).thenAnswer(invocation -> {
            LambdaQueryWrapper<Column> wrapper = invocation.getArgument(0);
            assertTrue(wrapper.getSqlSegment().endsWith("FOR UPDATE"));
            return column(1, 1);
        });
        when(links.insert(any(ArticleColumn.class))).thenReturn(1);
        saveLinks(List.of(10));
        for (String method : List.of("addArticle", "updateArticle")) {
            assertNotNull(ArticleServiceImpl.class.getMethod(method, com.zcx.chenstack.domain.dto.ArticleDto.class)
                    .getAnnotation(org.springframework.transaction.annotation.Transactional.class));
        }
    }

    @Test void publicDetailQueryExcludesEveryNonPublicStateAndForeignOwner() {
        login(2);
        when(columns.selectById(10)).thenReturn(column(1, 1));
        when(links.selectList(any())).thenReturn(java.util.stream.IntStream.rangeClosed(20, 26)
                .mapToObj(id -> new ArticleColumn().setArticleId(id).setColumnId(10).setSort(id)).toList());
        when(articles.selectList(any())).thenAnswer(invocation -> {
            LambdaQueryWrapper<Article> wrapper = invocation.getArgument(0);
            String sql = wrapper.getSqlSegment();
            for (String field : List.of("user_id", "is_deleted", "examine_status", "edit_status", "visible_range")) {
                assertTrue(sql.contains(field), field);
            }
            return List.of(article(1));
        });
        var detail = columnService.getColumnDetail(10);
        assertEquals(1, detail.getArticles().size());
        assertEquals(1, detail.getArticleCount());
    }

    @Test void ownerDetailStillRejectsCorruptForeignRelationsButKeepsOwnPendingDrafts() {
        when(columns.selectById(10)).thenReturn(column(1, 1));
        when(links.selectList(any())).thenReturn(java.util.stream.IntStream.rangeClosed(20, 26)
                .mapToObj(id -> new ArticleColumn().setArticleId(id).setColumnId(10).setSort(id)).toList());
        when(articles.selectList(any())).thenAnswer(invocation -> {
            LambdaQueryWrapper<Article> wrapper = invocation.getArgument(0);
            assertTrue(wrapper.getSqlSegment().contains("user_id"));
            assertFalse(wrapper.getSqlSegment().contains("visible_range"));
            return List.of(article(1).setExamineStatus(0).setEditStatus(1).setVisibleRange(1));
        });
        assertEquals(1, columnService.getColumnDetail(10).getArticles().size());
    }

    @Test void privateRejectedOrDeletedColumnsAreUnavailableToOtherAccounts() {
        login(2);
        for (Column denied : List.of(column(1, 1).setShowStatus(1), column(1, 2), column(1, 1).setIsDeleted(1))) {
            when(columns.selectById(10)).thenReturn(denied);
            assertThrows(BlogException.class, () -> columnService.getColumnDetail(10));
        }
        verifyNoInteractions(articles, links);
    }

    @Test void anonymousColumnPickerOnlyReturnsPublicApprovedColumnsAndPublicCount() {
        SecurityContextHolder.clearContext();
        when(columns.selectList(any())).thenAnswer(invocation -> {
            LambdaQueryWrapper<Column> wrapper = invocation.getArgument(0);
            assertTrue(wrapper.getSqlSegment().contains("show_status"));
            assertTrue(wrapper.getSqlSegment().contains("examine_status"));
            return List.of(column(1, 1));
        });
        when(columns.selectPublicArticleCounts(List.of(10))).thenReturn(List.of(new Column().setId(10).setArticleCount(1)));
        assertEquals(1, columnService.getColumnList().getFirst().getArticleCount());
    }

    @Test void clientCannotTransferColumnOwnership() {
        when(columns.selectById(10)).thenReturn(column(1, 1));
        when(columns.updateById(any(Column.class))).thenAnswer(invocation -> {
            assertEquals(1, ((Column) invocation.getArgument(0)).getUserId());
            return 1;
        });
        columnService.updateColumn(new ColumnDto().setId(10).setUserId(2).setName("专栏").setSort(1));
        verify(columns).updateById(any(Column.class));
    }

    @Test void guestCannotCreateColumnAndCrossAccountCannotSortOrRemove() {
        SecurityContextHolder.clearContext();
        assertThrows(BlogException.class, () -> columnService.addColumn(new ColumnDto().setUserId(1).setName("spoof")));
        login(2);
        when(columns.selectById(10)).thenReturn(column(1, 1));
        assertThrows(BlogException.class, () -> columnService.updateColumnArticleSort(10, List.of()));
        assertThrows(BlogException.class, () -> columnService.removeArticleFromColumn(10, 20));
        verifyNoInteractions(links);
    }

    @Test @SuppressWarnings("unchecked") void articleMetadataNeverExposesPrivatePendingOrForeignColumn() {
        List<Column> fixture = List.of(column(1, 1), column(1, 1).setId(11).setShowStatus(1),
                column(1, 0).setId(12), column(1, 2).setId(13), column(2, 1).setId(14),
                column(1, 1).setId(15).setIsDeleted(1));
        login(2);
        List<ColumnVo> publicResult = ReflectionTestUtils.invokeMethod(articleService, "filterVisibleColumns", fixture, 1);
        assertEquals(List.of(10), publicResult.stream().map(ColumnVo::getId).toList());
        login(1);
        List<ColumnVo> ownerResult = ReflectionTestUtils.invokeMethod(articleService, "filterVisibleColumns", fixture, 1);
        assertEquals(List.of(10, 11, 12), ownerResult.stream().map(ColumnVo::getId).toList());
    }

    @Test void publicCountQueryExcludesNonPublicAndDeletedRelations() {
        var configuration = new MybatisConfiguration();
        configuration.addMapper(ColumnMapper.class);
        var statement = configuration.getMappedStatement(ColumnMapper.class.getName() + ".selectPublicArticleCounts");
        var bound = statement.getBoundSql(Map.of("columnIds", List.of(10)));
        String sql = bound.getSql().replace("?", "10");
        assertTrue(sql.contains("a.user_id = c.user_id"));
        assertTrue(sql.contains("ac.is_deleted = 0"));
        for (String condition : List.of("a.is_deleted = 0", "a.examine_status = 1", "a.edit_status = 0", "a.visible_range = 0", "c.is_deleted = 0")) {
            assertTrue(sql.contains(condition), condition);
        }
    }

    @Test void editorMetadataRejectsForeignRejectedAndDeletedColumns() {
        var editor = new EditableArticleService(articles);
        ReflectionTestUtils.setField(editor, "articleColumnMapper", links);
        ReflectionTestUtils.setField(editor, "columnMapper", columns);
        when(articles.selectOne(any())).thenReturn(article(1));
        when(links.selectList(any())).thenReturn(List.of(new ArticleColumn().setArticleId(20).setColumnId(10)));
        when(columns.selectBatchIds(anyCollection())).thenReturn(List.of(column(1, 1), column(1, 0).setId(11),
                column(2, 1).setId(12), column(1, 2).setId(13), column(1, 1).setId(14).setIsDeleted(1)));
        assertEquals(List.of(10, 11), editor.loadForUser(1, 20).getColumns().stream().map(ColumnVo::getId).toList());
    }

}
