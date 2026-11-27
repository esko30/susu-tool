"""
Прототип: отметка посещаемости в Moodle ЮУрГУ (edu.susu.ru) через мобильный API.

Что делает:
  1. Спрашивает логин и пароль Moodle (пароль не отображается и никуда не сохраняется)
     и получает токен мобильного приложения.
  2. Показывает, кто вы и какие функции посещаемости сервер разрешает.
  3. Показывает ваши курсы.
  4. Показывает сегодняшние занятия с посещаемостью во всех курсах.
  5. Если преподаватель разрешил отмечаться самому — предлагает отметиться.
     Отметка ставится только после вашего явного «да».

Запуск:
  python tools/moodle_attendance.py            — всё прочитать и записать отчёт tools/moodle_report.json
  python tools/moodle_attendance.py --mark N   — отметиться на занятии N из отчёта (спросит «да»)

Логин и пароль берутся из tools/moodle_login.txt (первая строка — логин, вторая — пароль), если файл есть,
иначе спрашиваются. Файл в .gitignore. В отчёт не попадают ни пароль, ни токен.
Нужен только Python 3.8+, без сторонних библиотек.
"""
import os
import getpass
import json
import re
import sys
import time
import urllib.parse
import urllib.request

SITE = "https://edu.susu.ru"

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass


def post(path, data):
    body = urllib.parse.urlencode(data).encode()
    req = urllib.request.Request(SITE + path, data=body, headers={"User-Agent": "SUSU-Tool-prototype"})
    with urllib.request.urlopen(req, timeout=40) as r:
        return json.loads(r.read().decode("utf-8"))


def flatten(prefix, value, out):
    """Moodle REST ждёт массивы и объекты как key[0][field]=..."""
    if isinstance(value, dict):
        for k, v in value.items():
            flatten(f"{prefix}[{k}]", v, out)
    elif isinstance(value, (list, tuple)):
        for i, v in enumerate(value):
            flatten(f"{prefix}[{i}]", v, out)
    else:
        out[prefix] = value


class Moodle:
    def __init__(self, token):
        self.token = token

    def call(self, fn, **params):
        data = {"wstoken": self.token, "wsfunction": fn, "moodlewsrestformat": "json"}
        for k, v in params.items():
            if isinstance(v, (dict, list, tuple)):
                flatten(k, v, data)
            else:
                data[k] = v
        res = post("/webservice/rest/server.php", data)
        if isinstance(res, dict) and res.get("exception"):
            raise RuntimeError(f"{fn}: {res.get('errorcode')} {res.get('message')}")
        return res


HERE = os.path.dirname(os.path.abspath(__file__))
LOGIN_FILE = os.path.join(HERE, "moodle_login.txt")
REPORT_FILE = os.path.join(HERE, "moodle_report.json")


def login():
    if os.path.exists(LOGIN_FILE):
        lines = [l.strip() for l in open(LOGIN_FILE, encoding="utf-8").read().splitlines()]
        user, password = (lines + ["", ""])[:2]
        if not user or not password:
            print(f"Заполните {LOGIN_FILE}: первая строка — логин, вторая — пароль.")
            sys.exit(1)
    else:
        user = input("Логин Moodle (edu.susu.ru): ").strip()
        password = getpass.getpass("Пароль (не отображается): ")
    res = post("/login/token.php", {"username": user, "password": password, "service": "moodle_mobile_app"})
    password = None
    if "token" not in res:
        print("Не получилось войти:", res.get("error") or res)
        sys.exit(1)
    return Moodle(res["token"])


def when(ts):
    return time.strftime("%d.%m %H:%M", time.localtime(int(ts)))


def report(m):
    """Только чтение: что разрешено, курсы, сегодняшние занятия и их статусы. Без пароля и токена."""
    out = {"time": time.strftime("%Y-%m-%d %H:%M:%S")}
    info = m.call("core_webservice_get_site_info")
    uid = info["userid"]
    fns = sorted(f["name"] for f in info.get("functions", []))
    out["site"] = {k: info.get(k) for k in ("sitename", "release", "version", "userid", "fullname", "lang")}
    out["attendance_functions"] = [f for f in fns if "attendance" in f]
    out["functions_count"] = len(fns)
    out["functions"] = fns
    out["autologin_available"] = "tool_mobile_get_autologin_key" in fns
    print(f"Вы вошли как {info.get('fullname')}, Moodle {info.get('release')}")
    print("Функции посещаемости:", ", ".join(out["attendance_functions"]) or "нет")

    courses = m.call("core_enrol_get_users_courses", userid=uid)
    out["courses"] = [{"id": c["id"], "fullname": c.get("fullname"), "shortname": c.get("shortname")} for c in courses]
    print(f"Курсов: {len(courses)}")

    # Какие модули посещаемости есть в курсах (даже если сегодня занятий нет).
    try:
        mods = m.call("core_course_get_courses_by_field", field="ids", value=",".join(str(c["id"]) for c in courses))
        out["courses_by_field_ok"] = bool(mods)
    except Exception as e:
        out["courses_by_field_error"] = str(e)
    try:
        atts = m.call("mod_attendance_get_attendances_by_courses", courseids=[c["id"] for c in courses]) \
            if "mod_attendance_get_attendances_by_courses" in fns else None
        out["attendances_by_courses"] = atts
    except Exception as e:
        out["attendances_by_courses_error"] = str(e)

    # Что лежит в курсах текущего учебного года: есть ли «Посещаемость» и какие ещё модули.
    y, mon = int(time.strftime("%Y")), int(time.strftime("%m"))
    study_year = f"{y}/{y + 1}" if mon >= 8 else f"{y - 1}/{y}"
    out["study_year"] = study_year
    current = [c for c in courses if (c.get("fullname") or "").strip().startswith(study_year)]
    out["current_courses"] = []
    for c in current:
        entry = {"id": c["id"], "fullname": c.get("fullname"), "modules": {}, "attendance": []}
        try:
            for sec in m.call("core_course_get_contents", courseid=c["id"]):
                for mod in sec.get("modules", []):
                    name = mod.get("modname")
                    entry["modules"][name] = entry["modules"].get(name, 0) + 1
                    if name == "attendance":
                        entry["attendance"].append({"cmid": mod.get("id"), "instance": mod.get("instance"),
                                                    "name": mod.get("name"), "url": mod.get("url"),
                                                    "visible": mod.get("visible"), "uservisible": mod.get("uservisible")})
        except Exception as e:
            entry["error"] = str(e)
        out["current_courses"].append(entry)
        mark_ = f" · посещаемость: {len(entry['attendance'])}" if entry["attendance"] else ""
        print(f"  {c.get('fullname')}{mark_}")

    # Как официальное приложение работает с «Посещаемостью»: плагины с поддержкой приложения и их экраны.
    try:
        sp = m.call("tool_mobile_get_plugins_supporting_mobile")
        plugins = sp.get("plugins", []) if isinstance(sp, dict) else []
        out["mobile_plugins"] = [{"component": pl.get("component"), "addon": pl.get("addon"),
                                  "handlers": pl.get("handlers")} for pl in plugins]
        att = [pl for pl in plugins if pl.get("component") == "mod_attendance"]
        print("Плагины с поддержкой приложения:", ", ".join(pl.get("component") or "?" for pl in plugins) or "нет")
    except Exception as e:
        out["mobile_plugins_error"] = str(e)
        att = []

    # Экран «Посещаемости» для первых найденных занятий — тот, что открывает официальное приложение (только просмотр).
    out["attendance_screens"] = []
    if att and "tool_mobile_get_content" in fns:
        handlers = att[0].get("handlers")
        try:
            hs = json.loads(handlers) if isinstance(handlers, str) else (handlers or {})
        except Exception:
            hs = {}
        methods = sorted({h.get("method") for h in (hs.values() if isinstance(hs, dict) else []) if isinstance(h, dict) and h.get("method")})
        out["attendance_methods"] = methods
        found = [(c, a_) for c in out.get("current_courses", []) for a_ in c.get("attendance", [])]
        out["open_marking"] = []
        for c, a_ in found:
            args = [{"name": "cmid", "value": str(a_["cmid"])}, {"name": "courseid", "value": str(c["id"])}]
            try:
                content = m.call("tool_mobile_get_content", component="mod_attendance", method="mobile_view_activity", args=args)
            except Exception as e:
                out["attendance_screens"].append({"course": c["fullname"], "cmid": a_["cmid"], "error": str(e)})
                continue
            html = "".join(t.get("html", "") for t in content.get("templates") or [])
            screen = {"course": c["fullname"], "cmid": a_["cmid"], "name": a_["name"],
                      "templates": content.get("templates"), "otherdata": content.get("otherdata"),
                      "javascript": (content.get("javascript") or "")[:4000]}
            out["attendance_screens"].append(screen)
            # Кнопка «Отметиться»: переход на другой экран плагина с параметрами занятия.
            for meth, raw in re.findall(r'method="(\w+)"[^>]*?\[args\]="([^"]*)"', html) + \
                             [(b_, a2) for a2, b_ in re.findall(r'\[args\]="([^"]*)"[^>]*?method="(\w+)"', html)]:
                if meth == "mobile_view_activity":
                    continue
                pairs = dict(re.findall(r"(\w+)\s*:\s*'?([\w.-]+)'?", raw))
                entry = {"course": c["fullname"], "cmid": a_["cmid"], "name": a_["name"], "method": meth, "args": pairs}
                try:
                    form = m.call("tool_mobile_get_content", component="mod_attendance", method=meth,
                                  args=[{"name": k, "value": v} for k, v in pairs.items()])
                    entry["form_templates"] = form.get("templates")
                    entry["form_otherdata"] = form.get("otherdata")
                except Exception as e:
                    entry["form_error"] = str(e)
                out["open_marking"].append(entry)
        print(f"«Посещаемостей» в {study_year}: {len(found)}; открыто для отметки сейчас: {len(out['open_marking'])}")
        for e in out["open_marking"]:
            print(f"  ✓ {e['course']} · {e['name']} · {e['method']} {e['args']}")

    sessions = []
    if "mod_attendance_get_courses_with_today_sessions" in fns:
        try:
            today = m.call("mod_attendance_get_courses_with_today_sessions", userid=uid)
            out["today_raw"] = today
            for c in today or []:
                for inst in c.get("attendance_instances", []):
                    for s_ in inst.get("today_sessions", []):
                        entry = {"n": len(sessions) + 1, "course": c.get("fullname"), "instance": inst.get("name"),
                                 "sessionid": s_["id"], "start": when(s_["sessdate"]), "duration_min": int(s_.get("duration", 0)) // 60,
                                 "studentscanmark": s_.get("studentscanmark")}
                        try:
                            det = m.call("mod_attendance_get_session", sessionid=s_["id"])
                            entry["statusset"] = det.get("statusset")
                            entry["statuses"] = [{"id": st["id"], "acronym": st.get("acronym"), "description": st.get("description")}
                                                 for st in det.get("statuses", [])]
                        except Exception as e:
                            entry["session_error"] = str(e)
                        sessions.append(entry)
        except Exception as e:
            out["today_error"] = str(e)
    out["today_sessions"] = sessions
    # Прямой API посещаемости на edu.susu.ru закрыт — печатаем только если он вдруг заработает.
    if sessions:
        print(f"Через прямой API занятий с посещаемостью: {len(sessions)}")
        for e in sessions:
            print(f"  {e['n']}. {e['course']} · {e['start']} · самоотметка: {'да' if e.get('studentscanmark') else 'нет'}")
    json.dump(out, open(REPORT_FILE, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(f"\nОтчёт: {REPORT_FILE}")
    return info, sessions


def mark(m, n=1):
    """
    Отметка, как в официальном приложении: форма плагина «Посещаемость» (mobile_user_form) даёт статусы,
    а кнопка «Отправить» вызывает экран занятия (mobile_view_activity) с выбранным статусом.
    """
    report(m)
    r = json.load(open(REPORT_FILE, encoding="utf-8"))
    opened = r.get("open_marking") or []
    if not (1 <= n <= len(opened)):
        print("Сейчас нет открытого для отметки занятия" + (f" с номером {n}" if opened else "") + ".")
        return
    e = opened[n - 1]
    html = "".join(t.get("html", "") for t in e.get("form_templates") or [])
    statuses = re.findall(r"<ion-label>([^<]+)</ion-label>\s*<ion-radio[^>]*value=\"(\d+)\"", html)
    present = next(((lbl, v) for lbl, v in statuses if "присутств" in lbl.lower()), None)
    if not present:
        print("Не нашёл в форме статус «Присутствовал» — не отмечаю. Статусы:", statuses)
        return
    needs_pass = "studentpass" in html and "type=\"password\"" in html
    studentpass = input("Пароль занятия от преподавателя: ").strip() if needs_pass else ""
    ok = input(f"Отметить «{present[0].strip()}» на «{e['course']}» ({e['name']})? Напишите «да»: ").strip().lower()
    if ok != "да":
        print("Не отмечаю.")
        return
    args = {"cmid": e["args"]["cmid"], "courseid": e["args"]["courseid"], "sessid": e["args"]["sessid"],
            "status": present[1], "studentpass": studentpass}
    res = m.call("tool_mobile_get_content", component="mod_attendance", method="mobile_view_activity",
                 args=[{"name": k, "value": v} for k, v in args.items()])
    page = "".join(t.get("html", "") for t in res.get("templates") or [])
    still_open = "mobile_user_form" in page
    print("Отмечено." if not still_open else "Сервер принял запрос, но кнопка отметки всё ещё на месте — проверьте в Moodle.")
    json.dump({"sent": args, "after": res.get("templates")}, open(os.path.join(HERE, "moodle_mark_result.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)


def main():
    m = login()

    info = m.call("core_webservice_get_site_info")
    uid = info["userid"]
    print(f"\nВы вошли как {info.get('fullname')} (id {uid}), Moodle {info.get('release')}")
    fns = {f["name"] for f in info.get("functions", [])}
    att = sorted(f for f in fns if f.startswith("mod_attendance"))
    print("Функции посещаемости, доступные приложению:", ", ".join(att) or "нет")

    courses = m.call("core_enrol_get_users_courses", userid=uid)
    print(f"\nВаши курсы ({len(courses)}):")
    for c in courses:
        print(f"  [{c['id']}] {c.get('fullname')}")

    if "mod_attendance_get_courses_with_today_sessions" not in fns:
        print("\nСервер не даёт приложению посещаемость — отметиться через API не получится.")
        return

    today = m.call("mod_attendance_get_courses_with_today_sessions", userid=uid)
    sessions = []
    for c in today or []:
        for inst in c.get("attendance_instances", []):
            for s in inst.get("today_sessions", []):
                sessions.append((c, inst, s))
    if not sessions:
        print("\nСегодня занятий с посещаемостью в ваших курсах нет.")
        return

    print("\nСегодняшние занятия с посещаемостью:")
    for i, (c, inst, s) in enumerate(sessions, 1):
        end = int(s["sessdate"]) + int(s.get("duration", 0))
        print(f"  {i}. {c.get('fullname')} · {inst.get('name')} · {when(s['sessdate'])}–{time.strftime('%H:%M', time.localtime(end))}"
              f" · самоотметка: {'да' if s.get('studentscanmark') else 'нет'} (сессия {s['id']})")

    if "mod_attendance_update_user_status" not in fns:
        print("\nОтметиться через API сервер не разрешает (нет mod_attendance_update_user_status).")
        return

    choice = input("\nНомер занятия, чтобы отметиться (Enter — ничего не делать): ").strip()
    if not choice.isdigit() or not (1 <= int(choice) <= len(sessions)):
        print("Ничего не отмечаю.")
        return
    c, inst, s = sessions[int(choice) - 1]
    detail = m.call("mod_attendance_get_session", sessionid=s["id"])
    statuses = detail.get("statuses", [])
    print("Статусы:", ", ".join(f"{st['id']}={st.get('description') or st.get('acronym')}" for st in statuses))
    present = next((st for st in statuses if (st.get("acronym") or "").upper() in ("P", "П", "ПР")
                    or "присут" in (st.get("description") or "").lower()), None)
    if not present:
        print("Не нашёл статус «Присутствовал» — не отмечаю.")
        return
    ok = input(f"Отметить «{present.get('description')}» на «{c.get('fullname')}», {when(s['sessdate'])}? Напишите «да»: ").strip().lower()
    if ok != "да":
        print("Не отмечаю.")
        return
    res = m.call("mod_attendance_update_user_status", sessionid=s["id"], studentid=uid, takenbyid=uid,
                 statusid=present["id"], statusset=detail.get("statusset", 0))
    print("Ответ сервера:", res)


if __name__ == "__main__":
    try:
        if len(sys.argv) >= 2 and sys.argv[1] == "--mark":
            mark(login(), int(sys.argv[2]) if len(sys.argv) >= 3 else 1)
        elif len(sys.argv) >= 2 and sys.argv[1] == "--interactive":
            main()
        else:
            report(login())
    except Exception as e:
        print("Ошибка:", e)
