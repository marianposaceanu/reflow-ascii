(ns reflow-ascii.generator
  "An EDN workbench using the same renderer as its exported diagrams."
  (:require [cljs.pprint :refer [pprint]]
            [cljs.reader :as edn]
            [clojure.string :as str]
            [goog.object :as object]
            [reflow-ascii.browser :as browser]
            [reflow-ascii.core :as diagram]
            [reflow-ascii.markup :as markup]))

(def ^:private examples
  {"decision"
   {:title "A small decision"
    :nodes [{:id :ready :label "Is the build ready?"}
            {:id :ship :label "Ship it"}
            {:id :fix :label "Fix the failing checks"}]
    :edges [{:from :ready :to :ship :label "yes"}
            {:from :ready :to :fix :label "no" :primary true}]}

   "join"
   {:title "Two paths, one destination"
    :nodes [{:id :start :label "Choose a route"}
            {:id :fast :label "Use the cache"}
            {:id :slow :label "Fetch fresh data"}
            {:id :done :label "Display the result"}]
    :edges [{:from :start :to :fast :label "cache hit"}
            {:from :start :to :slow :label "cache miss"}
            {:from :fast :to :done}
            {:from :slow :to :done}]}

   "loop"
   {:title "A retry loop"
    :nodes [{:id :request :label "Make a request"}
            {:id :check :label "Did it succeed?"}
            {:id :done :label "Use the response"}]
    :edges [{:from :request :to :check}
            {:from :check :to :request :label "retry"}
            {:from :check :to :done :label "yes"}]}})

(defn- select [selector] (js/document.querySelector selector))
(defn- listen! [^js element event handler]
  (.addEventListener element event handler))
(defn- pretty [value] (with-out-str (pprint value)))

(defn- download! [filename type text]
  (let [blob (js/Blob. #js [text] #js {:type type})
        url (js/URL.createObjectURL blob)
        link (js/document.createElement "a")]
    (set! (.-href link) url)
    (set! (.-download link) filename)
    (.appendChild (.-body js/document) link)
    (.click link)
    (.remove link)
    (js/setTimeout #(js/URL.revokeObjectURL url) 1000)))

(defn- standalone-html [graph runtime]
  (str "<!doctype html><html lang=\"en\"><meta charset=\"utf-8\">"
       "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
       "<title>" (diagram/escape-html (or (:title graph) "ASCII diagram"))
       "</title><style>body{margin:0;padding:24px;box-sizing:border-box}"
       "pre{box-sizing:border-box;max-width:100%;min-width:0;overflow-x:auto;"
       "white-space:pre;overflow-wrap:normal;word-break:normal;direction:ltr;"
       "font:14px/1.55 ui-monospace,monospace;font-variant-ligatures:none;"
       "font-kerning:none;padding:16px;background:#2e302c;color:#f4f3e8}"
       "pre code{font:inherit;white-space:inherit;padding:0}</style><body>"
       (markup/render graph {:id "diagram" :columns 100})
       "<script type=\"module\">import {enhanceAll} from "
       "'data:text/javascript;base64," runtime "';enhanceAll();</script>"
       "</body></html>"))

(defn- keyword-ids [graph]
  (let [node #(update % :id keyword)
        edge #(-> % (update :from keyword) (update :to keyword))]
    (cond-> (-> graph (update :nodes (partial mapv node))
                     (update :edges (partial mapv edge)))
      (:root graph) (update :root keyword))))

(defn init! []
  (let [pre (select "#playground-diagram")
        source (select (str "#" (.getAttribute pre "data-ascii-source")))
        original (-> (js/JSON.parse (.-textContent source))
                     (js->clj :keywordize-keys true)
                     keyword-ids)
        editor (select "#graph-source")
        errors (select "#editor-error")
        frame (select "#diagram-frame")
        width (select "#width")
        output (select "#width-value")
        status (select "#layout-status")
        toggle (select "#toggle")
        runtime (str/trim (.-textContent (select "#runtime-source")))
        state (atom {:graph original :enabled? true})]
    (letfn [(enable! []
              (swap! state assoc :controller
                     (browser/enhance! pre (:graph @state))))
            (disable! []
              (when-let [controller (:controller @state)]
                ((:destroy! controller)))
              (swap! state dissoc :controller))
            (fallback-status! []
              (set! (.-textContent status)
                    "Static fallback · horizontal scrolling when needed"))
            (apply-model! [graph]
              ;; Validate before touching the previous working diagram.
              (let [fallback (diagram/render graph {:columns 100})]
                (disable!)
                (swap! state assoc :graph graph)
                (set! (.-textContent pre) fallback)
                (set! (.-value editor) (pretty graph))
                (set! (.-hidden errors) true)
                (if (:enabled? @state) (enable!) (fallback-status!))))
            (resize! [value]
              (set! (.-value width) value)
              (set! (.. frame -style -width) (str value "px"))
              (set! (.-value output) (str value " px"))
              (doseq [button (array-seq (js/document.querySelectorAll "[data-width]"))]
                (.setAttribute button "aria-pressed"
                               (str (= (.getAttribute button "data-width")
                                       (str value))))))
            (render-editor! []
              (try
                (when (> (count (.-value editor)) 100000)
                  (throw (ex-info "The model is too large." {})))
                (apply-model! (edn/read-string (.-value editor)))
                (catch :default error
                  (set! (.-textContent errors) (.-message error))
                  (set! (.-hidden errors) false))))
            (export! [format]
              (let [graph (:graph @state)]
                (case format
                  "ascii" (download! "diagram.txt" "text/plain;charset=utf-8"
                                     (str (.-textContent pre) "\n"))
                  "edn" (download! "diagram.edn" "application/edn;charset=utf-8"
                                   (pretty graph))
                  "json" (download! "diagram.json" "application/json;charset=utf-8"
                                    (str (js/JSON.stringify
                                          (clj->js graph :keyword-fn diagram/keyword-text)
                                          nil 2) "\n"))
                  "html" (download! "diagram.html" "text/html;charset=utf-8"
                                    (standalone-html graph runtime)))))]
      (listen! pre "ascii:render"
               (fn [event]
                 (let [detail (.-detail event)]
                   (set! (.-textContent status)
                         (str (object/get detail "mode") " layout · "
                              (object/get detail "columns") " columns available · "
                              (object/get detail "width") " used")))))
      (set! (.-value editor) (pretty original))
      (doseq [element (array-seq (js/document.querySelectorAll "[data-js-control]"))]
        (set! (.-disabled element) false))
      (enable!)
      (listen! width "input" #(resize! (.-value width)))
      (doseq [button (array-seq (js/document.querySelectorAll "[data-width]"))]
        (listen! button "click" #(resize! (.getAttribute button "data-width"))))
      (listen! (select "#render-graph") "click" render-editor!)
      (listen! (select "#example") "change"
               #(apply-model! (get examples (.. % -target -value) original)))
      (listen! toggle "click"
               (fn []
                 (if (:enabled? @state)
                   (do (disable!) (fallback-status!))
                   (enable!))
                 (swap! state update :enabled? not)
                 (set! (.-textContent toggle)
                       (str "Responsive: " (if (:enabled? @state) "on" "off")))))
      (doseq [button (array-seq (js/document.querySelectorAll "[data-export]"))]
        (listen! button "click" #(export! (.getAttribute button "data-export")))))))
