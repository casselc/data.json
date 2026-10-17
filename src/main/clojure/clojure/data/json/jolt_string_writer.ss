;; Explicit string-output adapter over the existing qualified JSONWriter site.
;; The public function identity comes from its DEFINING namespace, not this
;; optional loader. No caller data, writer or dispatch result is retained.
(let ()
 (define (make-output original write-var hook-var options-var stock expected-hook render)
  (unless (and (pvec? stock) (fx=? (pvec-count stock) 11)
               (eq? (pvec-nth! stock 10)
                    (keyword "clojure.data.json" "native-writer-v1")))
    (error 'native-string-output "Native writer ABI mismatch"))
  (lambda (value)
    (let ((write (var-cell-deref write-var))
          (options (var-cell-deref options-var)))
      (if (and (eq? write original)
               (if expected-hook
                   (eq? (var-cell-deref hook-var) expected-hook)
                   (jolt-nil? (var-cell-deref hook-var)))
               (pvec? value) (fx=? (pvec-count value) 0)
               (eq? options (pvec-nth! stock 9))
               (eq? (pmap-fast-get options djn-indent pmap-absent) #f)
               (eq? (var-cell-root djn-method-cell) (pvec-nth! stock 0)))
          ;; Resolve ONCE. Declining after resolving would replay arbitrary
          ;; receiver-predicate effects; call a selected custom method directly.
          (if render (render value options stock)
          (let ((impl (djn-json-writer-site value)))
            (if (eq? impl (pvec-nth! stock 8)) "[]"
                (let ((writer (host-new "StringWriter")))
                  (jolt-invoke3 impl value writer options)
                  (djn-sb-str writer)))))
          (jolt-invoke1 write value)))))
 (case-lambda
   ((original write-var hook-var options-var stock)
    (make-output original write-var hook-var options-var stock #f #f))
   ((original write-var hook-var options-var stock expected-hook render)
    (make-output original write-var hook-var options-var stock expected-hook render))))
