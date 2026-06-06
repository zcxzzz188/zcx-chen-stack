package com.zcx.chenstack.controller;

import com.zcx.chenstack.aspect.RateLimit;
import com.zcx.chenstack.domain.result.Result;
import com.zcx.chenstack.domain.vo.HistoryVo;
import com.zcx.chenstack.domain.vo.PageVo;
import com.zcx.chenstack.service.HistoryService;
import jakarta.annotation.Resource;
import jakarta.validation.constraints.NotNull;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * @author zcx
 * @since 2025-09-27
 */
@RateLimit(30)
@Validated
@RestController
@RequestMapping("/history")
public class HistoryController {

    @Resource
    private HistoryService historyService;

    /**
     * 分页获取当前用户的文章浏览记录（倒序）
     *
     * @param pageNum  页码
     * @param pageSize 每页大小
     * @return 分页浏览历史数据
     */
    @RateLimit
    @GetMapping("/list")
    public Result<PageVo<List<HistoryVo>>> getUserHistoryList(@RequestParam(defaultValue = "1") @NotNull(message = "页码不能为空") Integer pageNum,
                                                              @RequestParam(defaultValue = "10") @NotNull(message = "每页大小不能为空") Integer pageSize) {
        PageVo<List<HistoryVo>> historyPageVo = historyService.getUserHistoryList(pageNum, pageSize);
        return Result.success(historyPageVo);
    }

    /**
     * 清除当前用户的所有浏览记录
     *
     * @return 清除的记录数量
     */
    @DeleteMapping("/clear")
    public Result<Integer> clearUserHistory() {
        int clearedCount = historyService.clearUserHistory();
        return Result.success(clearedCount);
    }

}
