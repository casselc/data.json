;; Generic StringPBR token decoder; not a WAL-specific parser.
;; No input/cursor mutation. Decline Unicode and malformed escapes so the
;; established reader supplies exact Unicode/error/position behavior.
;; Every scan advances, except one pure reinspection at the first escape.
;; Ordinary copied runs are disjoint; output belongs to this token invocation.
(lambda (s start)
  (let ((n (string-length s)))
    (let loop ((i start) (run start) (out #f) (extract #f))
      (cond
        ((fx>= i n) #f)
        ((char=? (string-ref s i) #\")
         (if out
             (begin
               (when (fx< run i) (put-string out s run (fx- i run)))
               (jolt-vector (extract) (fx+ i 1)))
             (jolt-vector (substring s start i) (fx+ i 1))))
        ((char=? (string-ref s i) #\\)
         (if (fx>= (fx+ i 1) n) #f
             (let ((decoded
                     (case (string-ref s (fx+ i 1))
                       ((#\" #\\ #\/) (string-ref s (fx+ i 1)))
                       ((#\b) (integer->char 8))
                       ((#\f) (integer->char 12))
                       ((#\n) #\newline)
                       ((#\r) #\return)
                       ((#\t) #\tab)
                       (else #f))))
               (cond
                 ((not decoded) #f)
                 ((not out)
                  (call-with-values open-string-output-port
                    (lambda (port get) (loop i run port get))))
                 (else
                  (when (fx< run i) (put-string out s run (fx- i run)))
                  (put-char out decoded)
                  (loop (fx+ i 2) (fx+ i 2) out extract))))))
        (else (loop (fx+ i 1) run out extract))))))
