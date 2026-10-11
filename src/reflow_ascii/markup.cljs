(ns reflow-ascii.markup
  "Build-time HTML: real fallback text and inert graph data from one source."
  (:require [clojure.string :as str]
            [reflow-ascii.core :as diagram]))

(defn- script-json [value]
  (-> (js/JSON.stringify (clj->js value :keyword-fn diagram/keyword-text))
      (str/replace "<" "\\u003c")
      (str/replace ">" "\\u003e")
      (str/replace "&" "\\u0026")
      (str/replace "\u2028" "\\u2028")
      (str/replace "\u2029" "\\u2029")))

(defn render
  "Render a <pre><code> fallback and its inert model. Give each diagram a unique id."
  ([graph] (render graph {}))
  ([graph {:keys [id] :or {id "ascii-diagram"} :as options}]
   (when-not (and (string? id) (re-matches #"[A-Za-z][\w.-]*" id))
     (throw (ex-info "Invalid diagram HTML id" {:id id})))
   (let [normalized (diagram/normalize graph)
         text (:text (diagram/layout-normalized normalized options))]
     (str "<pre id=\"" id "\" data-ascii data-ascii-source=\"" id "-model\""
          " tabindex=\"0\" role=\"img\" aria-label=\""
          (diagram/escape-html (diagram/describe-normalized normalized))
          "\"><code>" (diagram/escape-html text) "</code></pre>\n"
          "<script type=\"application/json\" id=\"" id "-model\">"
          (script-json graph) "</script>"))))
