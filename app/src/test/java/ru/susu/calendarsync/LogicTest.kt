package ru.susu.calendarsync

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

/** Рейтинг и прогноз по БРС: формула сверена с порталом на реальных журналах. */
class ForecastTest {
    private fun cur(name: String, point: Double, max: Double, weight: String) =
        PointEvent("Текущий контроль", name, point, max, null, weight)
    private fun exam(max: Double, weight: String, point: Double = 0.0) =
        PointEvent(Forecast.ATTESTATION, "Экзамен", point, max, null, weight)

    private fun subject(current: Double?, control: String, vararg events: PointEvent) = SubjectGrades(
        "d1", "Дисциплина", 7, "1", "", control, "", current, "", "", current, current, events.toList(),
    )

    // Веса 12, 10×6, 15, 13 (сумма 100) и экзамен с весом 40: на портале текущий рейтинг 19.
    private val big = subject(19.0, "Экзамен",
        cur("опрос", 0.0, 64.0, "12"), cur("кр1", 18.0, 20.0, "10"), cur("кр2", 20.0, 20.0, "10"),
        cur("кр3", 0.0, 20.0, "10"), cur("кр4", 0.0, 20.0, "10"), cur("кр5", 0.0, 30.0, "10"),
        cur("кр6", 0.0, 100.0, "10"), cur("лр1", 0.0, 14.0, "15"), cur("лр2", 0.0, 23.0, "13"),
        exam(30.0, "40"),
    )

    // Веса 0,1 × 6 и 0,4, зачётное мероприятие с весом 1: на портале 27,14.
    private val small = subject(27.14, "Зачёт",
        cur("з1", 5.0, 7.0, "0.1"), cur("з2", 3.0, 3.0, "0.1"), cur("з3", 12.0, 12.0, "0.1"),
        cur("з4", 0.0, 7.0, "0.1"), cur("з5", 0.0, 8.0, "0.1"), cur("з6", 0.0, 7.0, "0.1"),
        cur("тест", 0.0, 20.0, "0.4"), exam(40.0, "1"),
    )

    @Test fun matchesPortalWithoutOverrides() {
        assertEquals(19.0, Forecast.current(big, emptyMap()), 1e-9)
        assertEquals(19.0, Forecast.projected(big, emptyMap()), 1e-9)
        assertEquals(27.14, Forecast.projected(small, emptyMap()), 1e-9)
    }

    @Test fun examIsNotPartOfCurrentRating() {
        // Полный балл за кр3 (вес 10 из 100) даёт +10, а не +10/140.
        val ov = mapOf(big.events[3].key to 20.0)
        assertEquals(29.0, Forecast.current(big, ov), 1e-9)
        val ov2 = mapOf(small.events[6].key to 20.0)
        assertEquals(67.14, Forecast.current(small, ov2), 1e-6)
    }

    @Test fun examShareFromPortalWeight() {
        assertEquals(0.4, Forecast.examShare(big), 1e-9)
        assertEquals(0.4, Forecast.examShare(small), 1e-9)
        val s = subject(0.0, "Экзамен", cur("a", 0.0, 10.0, "0.2"), exam(4.0, "0.4"))
        assertEquals(0.4, Forecast.examShare(s), 1e-9)
    }

    @Test fun withExamWhenRefusingBrs() {
        val ov = mapOf(big.events.last().key to 30.0)
        assertEquals(100.0, Forecast.examPercent(big, ov)!!, 1e-9)
        assertEquals(0.6 * 19 + 0.4 * 100, Forecast.projected(big, ov), 1e-9)
        assertNull(Forecast.examPercent(big, emptyMap()))
    }

    @Test fun neededOnRemainingAndOnExam() {
        assertEquals(listOf("опрос", "кр3", "кр4", "кр5", "кр6", "лр1", "лр2"), Forecast.remaining(big, emptyMap()).map { it.name })
        assertEquals((60 - 19) / 0.8, Forecast.needed(big, emptyMap(), 60.0)!!, 1e-9)
        assertEquals((60 - 0.6 * 19) / 0.4, Forecast.neededExam(big, emptyMap(), 60.0)!!, 1e-9)
        val none = subject(50.0, "Зачёт", cur("a", 5.0, 10.0, "1"))
        assertNull(Forecast.neededExam(none, emptyMap(), 60.0))
        assertNull(Forecast.needed(none, emptyMap(), 60.0))
    }

    @Test fun bonusesAddOnTop() {
        val s = subject(50.0, "Экзамен", cur("a", 5.0, 10.0, "1"), PointEvent(Forecast.BONUS, "бонус", 0.0, 5.0, null, "1"))
        assertEquals(53.0, Forecast.current(s, mapOf(s.events[1].key to 3.0)), 1e-9)
    }

    @Test fun neverNegative() {
        val s = subject(10.0, "Экзамен", cur("a", 10.0, 10.0, "1"))
        assertEquals(0.0, Forecast.current(s, mapOf(s.events[0].key to 0.0)), 1e-9)
    }

    @Test fun marksByThresholds() {
        assertEquals("5 · отлично", Forecast.markFor(85.0, false))
        assertEquals("4 · хорошо", Forecast.markFor(84.9, false))
        assertEquals("3 · удовлетворительно", Forecast.markFor(60.0, false))
        assertEquals("2 · неудовлетворительно", Forecast.markFor(59.9, false))
        assertEquals("зачтено", Forecast.markFor(60.0, true))
        assertEquals("не зачтено", Forecast.markFor(59.0, true))
    }

    @Test fun creditDetection() {
        assertTrue(Forecast.isCredit(subject(0.0, "Зачёт")))
        assertTrue(Forecast.isCredit(subject(0.0, "зачет")))
        assertFalse(Forecast.isCredit(subject(0.0, "Дифф. зачёт")))
        assertFalse(Forecast.isCredit(subject(0.0, "Экзамен")))
    }

    @Test fun numberFormatting() {
        assertEquals("—", Grades.fmt(null))
        assertEquals("85", Grades.fmt(85.0))
        assertEquals("85.5", Grades.fmt(85.50))
        assertEquals("7.25", Grades.fmt(7.25))
    }
}

/** Разбор занятий из ответа портала. */
class ScheduleParseTest {
    private fun lessonJson() = JSONObject()
        .put("eventDate", "2026-11-10T00:00:00")
        .put("beginTime", "08:00:00")
        .put("endTime", "09:35:00")
        .put("subject", "Математический анализ")
        .put("eventType", "Лекция")
        .put("location", JSONObject.NULL)
        .put("instructors", JSONArray().put(JSONObject().put("name", "Иванов И. И.").put("location", "1-201")))
        .put("groups", JSONArray().put("КЭ-101").put("КЭ-102"))

    @Test fun parsesLesson() {
        val l = SusuApi.parseLesson(lessonJson())!!
        assertEquals("2026-11-10", l.date)
        assertEquals("08:00", l.beginTime)
        assertEquals("09:35", l.endTime)
        assertEquals("Математический анализ", l.subject)
        assertEquals("1-201", l.location)
        assertTrue(l.details.contains("Преподаватель: Иванов И. И."))
        assertTrue(l.details.contains("Группы: КЭ-101, КЭ-102"))
    }

    @Test fun emptySubjectBecomesGeneric() {
        assertEquals("Занятие", SusuApi.parseLesson(lessonJson().put("subject", ""))!!.subject)
    }

    @Test fun rejectsBrokenTimes() {
        assertNull(SusuApi.parseLesson(lessonJson().put("beginTime", "8")))
        assertNull(SusuApi.parseLesson(lessonJson().put("eventDate", JSONObject.NULL)))
    }

    @Test fun lessonJsonRoundTrip() {
        val l = SusuApi.parseLesson(lessonJson())!!
        assertEquals(l, Lesson.fromJson(l.toJson()))
    }

    @Test fun diffFindsAddedAndIgnoresPast() {
        val a = Lesson("Физика", "Лекция", "2026-11-10", "08:00", "09:35", "1-201", "")
        val b = a.copy(subject = "Химия", beginTime = "09:45", endTime = "11:20")
        val past = a.copy(date = "2026-01-10")
        assertTrue(diffLessons(listOf(a), listOf(a), "2026-11-01").isEmpty())
        assertTrue(diffLessons(listOf(a), listOf(a, b), "2026-11-01").isNotEmpty())
        assertTrue(diffLessons(listOf(a, past), listOf(a), "2026-11-01").isEmpty())
    }
}

/** Напоминания планера. */
class RemindTest {
    @Test fun specRoundTrip() {
        for (r in Remind.PRESETS + Remind(90) + Remind(days = 3, time = "18:30")) {
            assertEquals(r, Remind.parse(r.spec()))
        }
    }

    @Test fun rejectsGarbage() {
        assertNull(Remind.parse(""))
        assertNull(Remind.parse("m-5"))
        assertNull(Remind.parse("d1@25"))
        assertNull(Remind.parse("x15"))
    }

    @Test fun labels() {
        assertEquals("В начале пары", Remind(0).label(true))
        assertEquals("В назначенное время", Remind(0).label(false))
        assertEquals("За 15 мин", Remind(15).label())
        assertEquals("За час", Remind(60).label())
        assertEquals("За 3 часа", Remind(180).label())
        assertEquals("За 1 ч 30 мин", Remind(90).label())
        assertEquals("За 2 дня", Remind(2880).label())
        assertEquals("Накануне в 20:00", Remind.EVENING.label())
        assertEquals("В тот же день в 08:00", Remind(days = 0, time = "08:00").label())
        assertEquals("За 5 дней в 10:00", Remind(days = 5, time = "10:00").label())
    }

    @Test fun plurals() {
        assertEquals("1 день", Remind.plural(1, "день", "дня", "дней"))
        assertEquals("3 дня", Remind.plural(3, "день", "дня", "дней"))
        assertEquals("11 дней", Remind.plural(11, "день", "дня", "дней"))
        assertEquals("21 день", Remind.plural(21, "день", "дня", "дней"))
        assertEquals("22 дня", Remind.plural(22, "день", "дня", "дней"))
    }

    private fun item(begin: String, vararg r: Remind) =
        PlannerItem(subject = "Физика", date = "2026-11-10", begin = begin, location = "", label = "", note = "", reminders = r.toList())

    private fun ms(s: String) = LocalDateTime.parse(s).atZone(CalendarSync.SUSU_ZONE).toInstant().toEpochMilli()

    @Test fun remindTimes() {
        assertEquals(listOf(ms("2026-11-10T09:45"), ms("2026-11-09T20:00")),
            item("10:00", Remind(15), Remind.EVENING).remindTimes())
        // Без времени — от 9:00.
        assertEquals(listOf(ms("2026-11-10T09:00")), item("", Remind(0)).remindTimes())
        assertTrue(item("10:00").remindTimes().isEmpty())
    }

    @Test fun jsonRoundTripAndLegacy() {
        val it = item("10:00", Remind(15), Remind(days = 2, time = "18:00")).copy(custom = true)
        assertEquals(it, PlannerItem.fromJson(it.toJson()))
        // Запись версии 2.1: одно напоминание по имени.
        val old = it.toJson().apply { remove("r"); put("remind", "HOUR") }
        assertEquals(listOf(Remind(60)), PlannerItem.fromJson(old).reminders)
        val none = it.toJson().apply { remove("r"); put("remind", "NONE") }
        assertTrue(PlannerItem.fromJson(none).reminders.isEmpty())
    }
}

/** Окна между парами, «сейчас / далее», недели и звонки. */
class TimelineTest {
    private fun l(begin: String, end: String, subject: String = "Пара $begin", date: String = "2026-11-10") =
        Lesson(subject, "Лекция", date, begin, end, "", "")

    @Test fun windowAfterSkippedPair() {
        val day = listOf(l("08:00", "09:35"), l("09:45", "11:20"), l("13:35", "15:10"))
        assertEquals(listOf(Timeline.Window("11:20", "13:35", 135)), Timeline.windows(day))
    }

    @Test fun regularBreaksAreNotWindows() {
        // Обычные перемены по 10 минут и большая — 30.
        val day = listOf(l("09:45", "11:20"), l("11:30", "13:05"), l("13:35", "15:10"))
        assertTrue(Timeline.windows(day).isEmpty())
    }

    @Test fun subgroupsAndOverlapsMerge() {
        val day = listOf(l("08:00", "09:35", "А"), l("08:00", "09:35", "Б"), l("08:50", "11:20", "В"), l("13:35", "15:10"))
        assertEquals(listOf(Timeline.Window("11:20", "13:35", 135)), Timeline.windows(day))
        assertTrue(Timeline.windows(listOf(l("08:00", "09:35"))).isEmpty())
        assertTrue(Timeline.windows(emptyList()).isEmpty())
    }

    @Test fun windowsIgnoreOrder() {
        val day = listOf(l("15:20", "16:55"), l("08:00", "09:35"))
        assertEquals(listOf(Timeline.Window("09:35", "15:20", 345)), Timeline.windows(day))
    }

    @Test fun durations() {
        assertEquals("45 мин", Timeline.duration(45))
        assertEquals("2 ч", Timeline.duration(120))
        assertEquals("1 ч 40 мин", Timeline.duration(100))
    }

    @Test fun nowAndNext() {
        val a = l("08:00", "09:35")
        val b = l("09:45", "11:20")
        val c = l("08:00", "09:35", date = "2026-11-11")
        val all = listOf(c, b, a)
        assertEquals(Timeline.Now(a, b), Timeline.now(all, LocalDateTime.parse("2026-11-10T09:00")))
        assertEquals(Timeline.Now(null, b), Timeline.now(all, LocalDateTime.parse("2026-11-10T09:40")))
        assertEquals(Timeline.Now(null, c), Timeline.now(all, LocalDateTime.parse("2026-11-10T12:00")))
        assertEquals(Timeline.Now(null, null), Timeline.now(all, LocalDateTime.parse("2026-11-30T12:00")))
        // Ровно в момент окончания пара уже не идёт.
        assertEquals(Timeline.Now(null, b), Timeline.now(all, LocalDateTime.parse("2026-11-10T09:35")))
    }

    @Test fun defaultWeekMovesOnAfterLastPair() {
        // 2026-11-02 — понедельник.
        val fri = l("13:35", "15:10", date = "2026-11-06")
        val nextMon = l("08:00", "09:35", date = "2026-11-09")
        val all = listOf(fri, nextMon)
        assertEquals(0L, Timeline.defaultWeek(all, LocalDateTime.parse("2026-11-06T14:00")))
        assertEquals(1L, Timeline.defaultWeek(all, LocalDateTime.parse("2026-11-06T15:10")))
        assertEquals(1L, Timeline.defaultWeek(all, LocalDateTime.parse("2026-11-08T12:00")))
        // На следующей неделе пусто — остаёмся на текущей.
        assertEquals(0L, Timeline.defaultWeek(listOf(fri), LocalDateTime.parse("2026-11-07T18:20")))
    }

    @Test fun upcomingSkipsFinishedAndKeepsOrder() {
        val a = l("08:00", "09:35")
        val b = l("09:45", "11:20")
        val c = l("08:00", "09:35", date = "2026-11-11")
        val far = l("08:00", "09:35", date = "2026-12-30")
        val got = Timeline.upcoming(listOf(far, c, b, a), LocalDateTime.parse("2026-11-10T09:00"))
        assertEquals(listOf(a, b, c), got)
        assertEquals(listOf(c), Timeline.upcoming(listOf(a, b, c), LocalDateTime.parse("2026-11-10T11:20")))
    }

    @Test fun hiddenLessonsAndWindows() {
        val a = l("08:00", "09:35")
        val b = l("09:45", "11:20")
        val c = l("13:35", "15:10")
        val subgroup = b.copy(location = "300/1")
        val hidden = setOf(Hidden.key(b))
        assertTrue(Hidden.isHidden(hidden, b))
        assertFalse(Hidden.isHidden(hidden, subgroup))
        // Скрытая пара освобождает время: окно от конца первой пары до третьей.
        val visible = listOf(a, b, c).filterNot { Hidden.isHidden(hidden, it) }
        assertEquals(listOf(Timeline.Window("09:35", "13:35", 240)), Timeline.windows(visible))
        // Старые скрытые пары чистятся через месяц.
        val old = Hidden.key(l("08:00", "09:35", date = "2026-09-01"))
        assertEquals(hidden, Hidden.prune(hidden + old, LocalDate.parse("2026-11-10")))
    }

    @Test fun weekStartsOnMonday() {
        var d = LocalDate.parse("2026-11-01")
        repeat(14) {
            val m = Timeline.weekStart(d)
            assertEquals(DayOfWeek.MONDAY, m.dayOfWeek)
            assertTrue(!m.isAfter(d) && m.plusDays(6) >= d)
            d = d.plusDays(1)
        }
    }

    @Test fun pairNumbers() {
        assertEquals(1, Timeline.pairNumber("08:00"))
        assertEquals(3, Timeline.pairNumber("11:30"))
        assertEquals(3, Timeline.pairNumber("11:40"))
        assertEquals(4, Timeline.pairNumber("13:35"))
        assertEquals(8, Timeline.pairNumber("20:25"))
        assertEquals(0, Timeline.pairNumber("??"))
        assertNotNull(Bells.find("08:00"))
    }
}

class WhatsNewTest {
    @Test fun versionOrder() {
        assertTrue(WhatsNew.newer("2.2", "2.1"))
        assertTrue(WhatsNew.newer("2.10", "2.9"))
        assertTrue(WhatsNew.newer("3", "2.9.9"))
        assertFalse(WhatsNew.newer("2.2", "2.2"))
        assertFalse(WhatsNew.newer("2.1", "2.2"))
        assertFalse(WhatsNew.newer("2.2", "2.2.0"))
    }
}
