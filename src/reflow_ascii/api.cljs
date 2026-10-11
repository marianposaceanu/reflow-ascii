(ns reflow-ascii.api
  "The JavaScript boundary. ClojureScript callers use core, markup, and browser directly."
  (:require [clojure.set :as set]
            [reflow-ascii.browser :as browser]
            [reflow-ascii.core :as diagram]
            [reflow-ascii.markup :as markup]))

(defn- data [value]
  (js->clj value :keywordize-keys true))

(defn- options [value]
  (-> (or (data value) {})
      (update :mode #(keyword (or % "auto")))
      (set/rename-keys {:onError :on-error})))

(defn- controller->js [{:keys [element refresh! update! destroy!]}]
  #js {:element element
       :refresh refresh!
       :update #(update! (data %))
       :destroy destroy!})

(defn render-diagram [graph opts]
  (diagram/render (data graph) (options opts)))

(defn layout-diagram [graph opts]
  (clj->js (diagram/layout (data graph) (options opts))))

(defn describe-diagram [graph]
  (diagram/describe (data graph)))

(defn render-markup [graph opts]
  (markup/render (data graph) (options opts)))

(defn enhance [pre graph opts]
  (controller->js (browser/enhance! pre (data graph) (options opts))))

(defn enhance-all [root opts]
  (into-array (map controller->js
                   (browser/enhance-all! (or root js/document) (options opts)))))
