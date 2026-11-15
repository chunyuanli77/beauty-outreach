import { openBlock as l, createElementBlock as o, Fragment as u, renderList as h, createElementVNode as e, toDisplayString as s, createCommentVNode as m, shallowRef as y, createApp as p, h as _ } from "/vendor/vue.runtime.esm-browser.prod.js";
const b = { class: "lead-row-name" }, g = { class: "lead-row-bio" }, k = { class: "lead-score" }, w = {
  key: 0,
  class: "check"
}, $ = ["checked", "aria-label", "onChange"], C = ["data-status"], f = ["aria-label", "onClick"], L = { key: 0 }, E = {
  colspan: "6",
  class: "lead-empty"
}, A = {
  __name: "LeadList",
  props: { items: { type: Array, required: !0 }, empty: { type: String, required: !0 } },
  emits: ["open-lead", "selection-change"],
  setup(r) {
    return (a, n) => (l(), o(u, null, [
      (l(!0), o(u, null, h(r.items, ({ lead: t, analysis: i, score: c, selection: v }) => (l(), o("tr", {
        key: t.id
      }, [
        e("td", null, [
          e("strong", b, s(t.username), 1),
          e("p", g, s(t.bio), 1)
        ]),
        e("td", null, s(t.region || "—") + " / " + s(t.language || "—"), 1),
        e("td", null, [
          e("span", k, s(c), 1)
        ]),
        e("td", null, [
          i.selectable ? (l(), o("label", w, [
            e("input", {
              type: "checkbox",
              checked: i.checked,
              "aria-label": `选择 ${t.username} 进行语义分析`,
              onChange: (d) => a.$emit("selection-change", t.id, d.currentTarget)
            }, null, 40, $),
            n[0] || (n[0] = e("span", null, "选择", -1))
          ])) : m("", !0),
          e("p", null, s(i.label), 1)
        ]),
        e("td", null, [
          e("span", {
            class: "pool-status",
            "data-status": t.selected ? "selected" : t.manual_decision === "REJECT" ? "rejected" : "idle"
          }, s(v), 9, C)
        ]),
        e("td", null, [
          e("button", {
            type: "button",
            "aria-label": `查看 ${t.username} 的详情`,
            onClick: (d) => a.$emit("open-lead", t)
          }, "详情", 8, f)
        ])
      ]))), 128)),
      r.items.length ? m("", !0) : (l(), o("tr", L, [
        e("td", E, s(r.empty), 1)
      ]))
    ], 64));
  }
}, V = {
  class: "overview-metrics",
  "aria-live": "polite"
}, q = { class: "overview-label" }, x = { class: "overview-value" }, B = {
  __name: "LeadOverview",
  props: { metrics: Array },
  setup(r) {
    const a = ["已导入线索", "当前目标池", "待规则评分"];
    return (n, t) => (l(), o("dl", V, [
      (l(), o(u, null, h(a, (i, c) => e("div", {
        key: i,
        class: "overview-item"
      }, [
        e("dt", q, s(i), 1),
        e("dd", x, s(r.metrics?.[c] ?? "—"), 1)
      ])), 64))
    ]));
  }
};
function R({ onOpenLead: r, onSelectionChange: a }) {
  const n = y({ items: [], empty: "正在加载线索…" });
  return p({ render: () => _(B, { metrics: n.value.metrics }) }).mount("#lead-overview"), p({ render: () => _(A, {
    items: n.value.items,
    empty: n.value.empty,
    onOpenLead: r,
    onSelectionChange: a
  }) }).mount("#lead-rows"), n;
}
export {
  R as mountLeadViews
};
