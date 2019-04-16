(ns metabase.async.util
  (:require [clojure.core.async :as a]
            [clojure.tools.logging :as log]
            [metabase.util.i18n :refer [trs]]
            [schema.core :as s])
  (:import clojure.core.async.impl.channels.ManyToManyChannel
           clojure.core.async.impl.buffers.PromiseBuffer))

(defn- promise-chan? [^ManyToManyChannel chan]
  (when chan
    (instance? PromiseBuffer (.buf chan))))

(def PromiseChan
  (s/constrained ManyToManyChannel promise-chan? "promise chan"))

(s/defn promise-canceled-chan :- PromiseChan
  [promise-chan :- PromiseChan]
  (let [canceled-chan (a/promise-chan)]
    (a/go
      (when (nil? (a/<! promise-chan))
        (a/>! canceled-chan ::canceled))
      (a/close! canceled-chan))
    canceled-chan))

;; TODO - don't really need this complicated stuff, can use promise channels, `promise-canceled-chan`, `a/pipe`, etc.
;; instead.
(s/defn single-value-pipe :- PromiseChan
  "Pipe that will forward a single message from `in-chan` to `out-chan`, closing both afterward. If `out-chan` is closed
  before `in-chan` produces a value, closes `in-chan`; this can be used to automatically cancel QP requests and the
  like.

  Returns a channel that will send a single message when such early-closing cancelation occurs. You can listen for
  this message to implement special cancelation/close behavior, such as canceling async jobs. This channel
  automatically closes when either `in-chan` or `out-chan` closes."
  [in-chan :- ManyToManyChannel, out-chan :- ManyToManyChannel]
  (let [canceled-chan (a/promise-chan)]
    ;; fire off a block that will wait for either in-chan to produce a result or out-chan to be closed
    (a/go
      (try
        (let [[result first-finished-chan] (a/alts! [in-chan out-chan])]
          (if (and (= first-finished-chan in-chan)
                   (some? result))
            ;; If `in-chan` (e.g. fn call result) finishes first and receives a result, forward result to `out-chan`
            (a/>! out-chan result)
            ;; Otherwise one of the two channels was closed (e.g. query cancelation) before `in-chan` returned a
            ;; result (e.g. QP result), pass a message to `canceled-chan`; `finally` block will close all three channels
            (a/>! canceled-chan ::canceled)))
        ;; Either way, close whichever of the channels is still open just to be safe
        (finally
          (a/close! out-chan)
          (a/close! in-chan)
          (a/close! canceled-chan))))
    ;; return the canceled chan in case someone wants to listen to it
    canceled-chan))

(defn- do-on-separate-thread* [out-chan canceled-chan f args]
  (future
    (if (a/poll! canceled-chan)
      (log/debug (trs "Output channel closed, will skip running {0}." f))
      (do
        (log/debug (trs "Running {0} on separate thread..." f))
        (try
          (when-let [result (apply f args)]
            (a/>!! out-chan result))
          (catch Throwable e
            (log/error e (trs "Caught error running {0}" f))
            (a/>!! out-chan e))
          (finally
            (a/close! out-chan)))))))

(defn do-on-separate-thread
  "Run `(apply f args)` on a separate thread, returns a channel to fetch the results. Closing this channel early will
  cancel the future running the function, if possible."
  [f & args]
  (let [out-chan      (a/promise-chan)
        canceled-chan (promise-canceled-chan out-chan)
        ;; Run `f` on a separarate thread because it's a potentially long-running QP query and we don't want to tie
        ;; up precious core.async threads
        futur         (do-on-separate-thread* out-chan canceled-chan f args)]
    (a/go
      (when (a/<! canceled-chan)
        (log/debug (trs "Request canceled, canceling future"))
        (future-cancel futur)))

    out-chan))
