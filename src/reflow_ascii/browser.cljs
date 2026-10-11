(ns reflow-ascii.browser
  "Progressive enhancement. Owns observation and DOM lifetime, never layout rules."
  (:require [reflow-ascii.core :as diagram]
            [reflow-ascii.measure :as measure]))

(defonce ^:private instances (js/WeakMap.))

(def ^:private managed-attributes
  ["role" "aria-label" "data-ascii-layout" "data-ascii-columns"])

(defn- attributes [^js element]
  (into {} (map (fn [name] [name (.getAttribute element name)]))
        managed-attributes))

(defn- set-attributes! [^js element attrs]
  (doseq [[name value] attrs]
    (if (nil? value)
      (.removeAttribute element name)
      (.setAttribute element name value))))

(defn- emit! [^js element name detail]
  (.dispatchEvent element
                  (js/CustomEvent. name #js {:bubbles true
                                            :detail (clj->js detail)})))

(defn- report! [pre on-error error]
  (emit! pre "ascii:error" {:error error})
  (when on-error (on-error error pre)))

(defn- text-target [^js pre]
  (let [child (.-firstElementChild pre)]
    (if (and (= 1 (.-length (.-children pre))) (= "CODE" (.-nodeName child)))
      child
      pre)))

(defn- restore-text! [^js target original-nodes state]
  (when-not (:original? @state)
    (.replaceChildren target)
    (doseq [node original-nodes] (.appendChild target node))
    (swap! state assoc :original? true)))

(defn- render! [^js pre ^js target original-nodes state mode]
  (let [{:keys [graph revision last-render metrics]} @state
        measured (measure/measure! pre target metrics)
        columns (min diagram/max-columns (:columns measured))
        render-key [revision columns]]
    (swap! state assoc :metrics measured)
    (when-not (= render-key last-render)
      (if (< columns diagram/min-columns)
        (do
          (restore-text! target original-nodes state)
          (set-attributes! pre {"data-ascii-layout" "fallback"
                                "data-ascii-columns" nil}))
        (let [result (diagram/layout-normalized graph {:columns columns :mode mode})]
          (set! (.-textContent target) (:text result))
          (swap! state assoc :original? false)
          (set-attributes! pre {"role" "img"
                                "aria-label" (diagram/describe-normalized graph)
                                "data-ascii-layout" (name (:mode result))
                                "data-ascii-columns" (str columns)})
          (emit! pre "ascii:render" result)))
      (swap! state assoc :last-render render-key))))

(defn enhance!
  "Enhance a pre; return {:element :refresh! :update! :destroy!}.
   Invalid models leave the original DOM untouched. Repeated calls reuse observers."
  ([pre graph] (enhance! pre graph {}))
  ([^js pre graph {:keys [mode on-error] :or {mode :auto}}]
   (when-not (= "PRE" (.-nodeName pre))
     (throw (ex-info "enhance! requires a <pre> element" {})))
   (when-not (contains? #{:auto :stacked :references} mode)
     (throw (ex-info "Unknown layout mode" {:mode mode})))
   (let [graph (diagram/normalize graph)]
     (if-let [existing (.get instances pre)]
       (do ((:update! existing) graph) existing)
       (let [document (.-ownerDocument pre)
             window (.-defaultView document)
             fonts (.-fonts document)
             target (text-target pre)
             original-nodes (vec (array-seq (.-childNodes target)))
             original-attrs (attributes pre)
             state (atom {:graph graph :revision 0 :original? true})]
         (letfn [(refresh! []
                   (when (and (not (:destroyed? @state))
                              (.-isConnected pre)
                              (pos? (.-clientWidth pre)))
                     (try
                       (render! pre target original-nodes state mode)
                       (catch :default error (report! pre on-error error)))))
                 (schedule! []
                   (when-not (or (:destroyed? @state) (:frame @state))
                     (swap! state assoc :frame
                            (.requestAnimationFrame
                             window
                             (fn []
                               (swap! state dissoc :frame)
                               (refresh!))))))
                 (fonts-changed! []
                   (swap! state dissoc :metrics)
                   (schedule!))]
           (let [observer (when-let [constructor (.-ResizeObserver window)]
                            (js/Reflect.construct constructor #js [schedule!]))
                 controller
                 {:element pre
                  :refresh! (fn []
                              (swap! state dissoc :metrics :last-render)
                              (refresh!))
                  :update! (fn [next-graph]
                             (let [next-graph (diagram/normalize next-graph)]
                               (swap! state #(-> %
                                                 (assoc :graph next-graph)
                                                 (update :revision inc)))
                               (refresh!)))
                  :destroy! (fn []
                              (when-not (:destroyed? @state)
                                (swap! state assoc :destroyed? true)
                                (when observer (.disconnect observer))
                                (.removeEventListener window "resize" schedule!)
                                (when fonts
                                  (.removeEventListener fonts "loadingdone" fonts-changed!))
                                (when-let [frame (:frame @state)]
                                  (.cancelAnimationFrame window frame))
                                (restore-text! target original-nodes state)
                                (set-attributes! pre original-attrs)
                                (.delete instances pre)))}]
             (.set instances pre controller)
             (when observer (.observe observer pre))
             (.addEventListener window "resize" schedule! #js {:passive true})
             (when fonts
               (.addEventListener fonts "loadingdone" fonts-changed!)
               (.then (.-ready fonts)
                      #(when-not (:destroyed? @state) (fonts-changed!))))
             (refresh!)
             controller)))))))

(defn enhance-all!
  "Enhance opted-in pre elements, each with an inert JSON model by script id."
  ([] (enhance-all! js/document {}))
  ([root] (enhance-all! root {}))
  ([^js root {:keys [on-error] :as options}]
   (let [selector "pre[data-ascii-source]"
         descendants (array-seq (.querySelectorAll root selector))
         elements (if (and (.-matches root) (.matches root selector))
                    (cons root descendants)
                    descendants)]
     (into []
           (keep (fn [^js pre]
                   (try
                     (let [id (.getAttribute pre "data-ascii-source")
                           source (.getElementById (.-ownerDocument pre) id)]
                       (when-not (and source
                                      (= "SCRIPT" (.-nodeName source))
                                      (= "application/json" (.-type source)))
                         (throw (ex-info "Missing application/json diagram model" {:id id})))
                       (enhance! pre
                                 (js->clj (js/JSON.parse (.-textContent source))
                                          :keywordize-keys true)
                                 options))
                     (catch :default error
                       (report! pre on-error error)
                       nil))))
           elements))))
