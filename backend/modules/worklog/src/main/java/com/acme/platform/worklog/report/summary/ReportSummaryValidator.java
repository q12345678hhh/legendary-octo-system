package com.acme.platform.worklog.report.summary;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 摘要校验：只保留能回溯到事实的内容。
 *
 * <p>规则：
 * <ul>
 *   <li>每条摘要/产出必须给出本周条目引用，非法引用整条丢弃；</li>
 *   <li>逐句检查数字，只要出现事实白名单之外的数字（含中文数量词）就丢弃该句；</li>
 *   <li>全部内容被丢弃时返回空结果，由上层改用时长模板。</li>
 * </ul>
 */
public class ReportSummaryValidator {

    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern NUMBER = Pattern.compile("(?<![\\d.])\\d+(?:\\.\\d+)?(?![\\d.])");
    private static final Pattern CHINESE_QUANTITY =
            Pattern.compile("[零一二三四五六七八九十百千万两]+(?:分钟|小时|天|项|条|个|次|周)");
    private static final Pattern SENTENCE = Pattern.compile("[^。！？!?；;\\n]+[。！？!?；;]?");
    private static final int MAX_POINTS = 5;
    private static final int MAX_HIGHLIGHTS = 3;
    private static final int MAX_TEXT = 200;

    public ValidatedSummary validate(ReportSummarizer.SummaryDraft draft, WeekFacts facts) {
        Set<String> allowed = allowedNumbers(facts);
        Set<UUID> entryIds = new LinkedHashSet<>();
        facts.entries().forEach(entry -> entryIds.add(entry.entryId()));

        int dropped = 0;
        List<ReportSummarizer.SummaryPoint> summary = new ArrayList<>();
        for (ReportSummarizer.SummaryPoint point : draft.summaryPoints()) {
            ReportSummarizer.SummaryPoint checked = checkPoint(point, allowed, entryIds);
            if (checked == null) {
                dropped++;
            } else if (summary.size() < MAX_POINTS) {
                summary.add(checked);
            } else {
                dropped++;
            }
        }

        List<ReportSummarizer.SummaryPoint> highlights = new ArrayList<>();
        for (ReportSummarizer.SummaryPoint point : draft.highlights()) {
            ReportSummarizer.SummaryPoint checked = checkPoint(point, allowed, entryIds);
            if (checked == null) {
                dropped++;
            } else if (highlights.size() < MAX_HIGHLIGHTS) {
                highlights.add(checked);
            } else {
                dropped++;
            }
        }

        String nextWeekPlan = "";
        if (draft.nextWeekPlan() != null && !draft.nextWeekPlan().isBlank()) {
            StringBuilder kept = new StringBuilder();
            Matcher matcher = SENTENCE.matcher(draft.nextWeekPlan().strip());
            while (matcher.find()) {
                String sentence = matcher.group().strip();
                if (sentence.isEmpty()) {
                    continue;
                }
                if (safe(sentence, allowed)) {
                    kept.append(sentence);
                } else {
                    dropped++;
                }
            }
            nextWeekPlan = kept.toString().strip();
        }

        return new ValidatedSummary(List.copyOf(summary), List.copyOf(highlights),
                nextWeekPlan, dropped);
    }

    private ReportSummarizer.SummaryPoint checkPoint(
            ReportSummarizer.SummaryPoint point, Set<String> allowed, Set<UUID> validEntryIds) {
        if (point == null || point.text() == null || point.entryIds() == null) {
            return null;
        }
        String text = point.text().strip();
        if (text.isEmpty() || text.length() > MAX_TEXT) {
            return null;
        }
        List<UUID> refs = point.entryIds().stream()
                .filter(validEntryIds::contains)
                .distinct()
                .toList();
        if (refs.isEmpty()) {
            return null;
        }
        StringBuilder kept = new StringBuilder();
        Matcher matcher = SENTENCE.matcher(text);
        while (matcher.find()) {
            String sentence = matcher.group().strip();
            if (!sentence.isEmpty() && safe(sentence, allowed)) {
                kept.append(sentence);
            }
        }
        String result = kept.toString().strip();
        if (result.isEmpty()) {
            return null;
        }
        return new ReportSummarizer.SummaryPoint(result, refs);
    }

    private boolean safe(String sentence, Set<String> allowed) {
        if (CHINESE_QUANTITY.matcher(sentence).find()) {
            return false;
        }
        String withoutDates = DATE.matcher(sentence).replaceAll(" ");
        Matcher numbers = NUMBER.matcher(withoutDates);
        while (numbers.find()) {
            if (!allowed.contains(normalize(numbers.group()))) {
                return false;
            }
        }
        return true;
    }

    private Set<String> allowedNumbers(WeekFacts facts) {
        Set<String> values = new LinkedHashSet<>();
        add(values, facts.totalMinutes());
        add(values, facts.confirmedDays());
        add(values, facts.entries().size());
        add(values, facts.days().size());
        facts.days().forEach(day -> add(values, day.minutes()));
        facts.items().forEach(item -> add(values, item.minutes()));
        facts.entries().forEach(entry -> add(values, entry.minutes()));
        facts.weekStart().datesUntil(facts.weekEnd().plusDays(1))
                .forEach(date -> values.add(date.format(DateTimeFormatter.ISO_LOCAL_DATE)));
        return values;
    }

    private static void add(Set<String> values, int minutes) {
        values.add(Integer.toString(minutes));
        double hours = minutes / 60.0;
        String oneDecimal = String.format(java.util.Locale.ROOT, "%.1f", hours);
        values.add(normalize(oneDecimal));
        if (Math.abs(hours - Math.rint(hours)) < 0.0001) {
            values.add(Integer.toString((int) Math.rint(hours)));
        }
    }

    private static String normalize(String value) {
        String text = value.strip();
        while (text.contains(".") && (text.endsWith("0") || text.endsWith("."))) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    /**
     * 校验后的摘要。
     *
     * @param droppedClaims 被丢弃的句子/条目数
     */
    public record ValidatedSummary(List<ReportSummarizer.SummaryPoint> summaryPoints,
                                   List<ReportSummarizer.SummaryPoint> highlights,
                                   String nextWeekPlan, int droppedClaims) {

        public boolean isEmpty() {
            return summaryPoints.isEmpty() && highlights.isEmpty() && nextWeekPlan.isEmpty();
        }
    }
}
