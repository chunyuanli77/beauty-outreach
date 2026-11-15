import { ref as N, reactive as c, computed as A, onMounted as B, openBlock as d, createElementBlock as u, Fragment as _, createElementVNode as e, renderList as S, toDisplayString as o, createCommentVNode as x, withModifiers as T, createTextVNode as b, withDirectives as v, vModelText as w, createApp as M } from "/vendor/vue.runtime.esm-browser.prod.js";
import { api as f } from "/workbench.js";
const P = {
  class: "panel panel-body stack",
  "aria-labelledby": "templates-title"
}, C = ["aria-busy"], I = { class: "workbench-preview" }, z = {
  key: 0,
  role: "status"
}, G = { key: 1 }, H = ["hidden"], J = ["aria-busy"], K = ["disabled"], Q = ["disabled"], R = { class: "actions" }, W = ["disabled"], X = ["hidden"], Y = ["hidden"], Z = {
  class: "panel panel-body stack",
  "aria-labelledby": "frequency-title"
}, $ = {
  key: 0,
  role: "status"
}, ee = ["aria-busy"], te = ["onUpdate:modelValue", "name", "min", "max", "disabled"], se = { class: "actions" }, re = ["disabled"], ne = ["hidden"], ae = ["hidden"], oe = {
  __name: "Settings",
  setup(ie) {
    const m = [
      ["globalLimit", "窗口内最多尝试（次）", 1, 100],
      ["windowSeconds", "全局窗口（秒）", 1, 3600],
      ["cooldownSeconds", "同一联系人冷却（秒）", 0, 86400],
      ["retrySeconds", "首次重试间隔（秒）", 1, 3600]
    ], p = N([]), l = c({ name: "", body: "" }), i = c({ loading: !0, error: "" }), r = c({ pending: !1, status: "", error: "" }), g = c(Object.fromEntries(m.map(([n]) => [n, ""]))), y = c({ loading: !0, ready: !1 }), a = c({ pending: !1, status: "", error: "" }), h = A(() => !y.ready || a.pending);
    async function q() {
      i.loading = !0, i.error = "";
      try {
        p.value = await f("templates");
      } catch (n) {
        i.error = n.message;
      } finally {
        i.loading = !1;
      }
    }
    function k(n) {
      for (const [t] of m) g[t] = n[t];
    }
    async function E() {
      try {
        k(await f("frequency")), y.ready = !0;
      } catch (n) {
        a.error = n.message;
      } finally {
        y.loading = !1;
      }
    }
    async function O() {
      if (r.pending) return;
      const n = l.name.trim(), t = l.body.trim();
      Object.assign(r, { pending: !0, status: "", error: "" });
      try {
        if (!n || !t) throw new Error("请输入模板名称和问候文案。");
        await f("templates", "POST", { name: n, body: t }), Object.assign(l, { name: "", body: "" }), r.status = "模板已保存。", await q();
      } catch (s) {
        r.error = s.message;
      } finally {
        r.pending = !1;
      }
    }
    async function j(n) {
      if (h.value) return;
      const t = Object.fromEntries(m.map(([s]) => [s, n.currentTarget.elements[s].valueAsNumber]));
      Object.assign(a, { pending: !0, status: "", error: "" });
      try {
        k(await f("frequency", "PUT", t)), a.status = "频控已保存。";
      } catch (s) {
        a.error = s.message;
      } finally {
        a.pending = !1;
      }
    }
    return B(() => {
      q(), E();
    }), (n, t) => (d(), u(_, null, [
      e("section", P, [
        t[5] || (t[5] = e("h2", { id: "templates-title" }, "问候模板", -1)),
        e("div", {
          id: "template-list",
          class: "stack",
          "aria-busy": i.loading
        }, [
          (d(!0), u(_, null, S(p.value, (s) => (d(), u("div", {
            key: s.id,
            class: "stack template-card"
          }, [
            e("h3", null, o(s.name), 1),
            e("div", I, o(s.body), 1)
          ]))), 128)),
          !p.value.length && i.loading ? (d(), u("p", z, "正在加载模板…")) : !p.value.length && !i.error ? (d(), u("p", G, "暂时没有模板。")) : x("", !0)
        ], 8, C),
        e("p", {
          id: "templates-error",
          class: "message error",
          role: "alert",
          hidden: !i.error
        }, o(i.error), 9, H),
        e("form", {
          id: "template-form",
          class: "workbench-form",
          "aria-busy": r.pending,
          onSubmit: T(O, ["prevent"])
        }, [
          e("label", null, [
            t[2] || (t[2] = b("模板名称", -1)),
            v(e("input", {
              id: "template-name",
              "onUpdate:modelValue": t[0] || (t[0] = (s) => l.name = s),
              name: "name",
              maxlength: "100",
              required: "",
              disabled: r.pending
            }, null, 8, K), [
              [w, l.name]
            ])
          ]),
          e("label", null, [
            t[3] || (t[3] = b("问候文案", -1)),
            v(e("textarea", {
              id: "template-body",
              "onUpdate:modelValue": t[1] || (t[1] = (s) => l.body = s),
              name: "body",
              rows: "4",
              maxlength: "1000",
              required: "",
              "aria-describedby": "template-help",
              disabled: r.pending
            }, null, 8, Q), [
              [w, l.body]
            ])
          ]),
          t[4] || (t[4] = e("p", {
            id: "template-help",
            class: "workbench-note"
          }, [
            e("code", null, "{{display_name}}"),
            b(" 会替换成用户名。修改内容可另存为新模板。")
          ], -1)),
          e("div", R, [
            e("button", {
              class: "primary",
              type: "submit",
              disabled: r.pending
            }, o(r.pending ? "正在保存…" : "新增模板"), 9, W)
          ])
        ], 40, J),
        e("p", {
          id: "template-status",
          class: "message",
          role: "status",
          hidden: !r.status
        }, o(r.status), 9, X),
        e("p", {
          id: "template-error",
          class: "message error",
          role: "alert",
          hidden: !r.error
        }, o(r.error), 9, Y)
      ]),
      e("section", Z, [
        t[7] || (t[7] = e("h2", { id: "frequency-title" }, "全局频控", -1)),
        y.loading ? (d(), u("p", $, "正在加载频控…")) : x("", !0),
        e("form", {
          id: "frequency-form",
          class: "workbench-form",
          "aria-busy": a.pending,
          onSubmit: T(j, ["prevent"])
        }, [
          (d(), u(_, null, S(m, ([s, F, U, D]) => e("label", { key: s }, [
            b(o(F) + " ", 1),
            v(e("input", {
              "onUpdate:modelValue": (L) => g[s] = L,
              name: s,
              type: "number",
              min: U,
              max: D,
              required: "",
              disabled: h.value
            }, null, 8, te), [
              [w, g[s]]
            ])
          ])), 64)),
          t[6] || (t[6] = e("p", { class: "workbench-note" }, "保存后适用于所有任务的后续发送尝试。暂时失败最多重试 2 次，仍受全局频控和联系人冷却限制。", -1)),
          e("div", se, [
            e("button", {
              class: "primary",
              type: "submit",
              disabled: h.value
            }, o(a.pending ? "正在保存…" : "保存频控"), 9, re)
          ])
        ], 40, ee),
        e("p", {
          id: "frequency-status",
          class: "message",
          role: "status",
          hidden: !a.status
        }, o(a.status), 9, ne),
        e("p", {
          id: "frequency-error",
          class: "message error",
          role: "alert",
          hidden: !a.error
        }, o(a.error), 9, ae)
      ])
    ], 64));
  }
}, V = document.getElementById("settings-app");
M(oe).mount(V);
V.removeAttribute("aria-busy");
