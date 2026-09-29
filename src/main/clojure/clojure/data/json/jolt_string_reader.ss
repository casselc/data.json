;; Generic StringPBR token decoder; not a WAL-specific parser.
;; No input/cursor mutation. Valid Unicode escapes use Jolt scalar semantics;
;; malformed/noncanonical escapes decline for original error/position behavior.
;; Every scan advances, except one pure reinspection at the first escape.
;; Ordinary copied runs are disjoint; output belongs to this token invocation.
(lambda (s start)
  (let ((n (string-length s)))
    (define (hex-unit-at start)
      (and (fx<= (fx+ start 4) n)
           (let hex ((i start) (end (fx+ start 4)) (value 0))
             (if (fx= i end) value
                 (let* ((cp (char->integer (string-ref s i)))
                        (digit (cond
                                 ((fx<= 48 cp 57) (fx- cp 48))
                                 ((fx<= 65 cp 70) (fx- cp 55))
                                 ((fx<= 97 cp 102) (fx- cp 87))
                                 (else #f))))
                   (and digit (hex (fx+ i 1) end (fx+ (fx* value 16) digit))))))))
    (define (unicode-at slash)
      (let ((head (hex-unit-at (fx+ slash 2))))
        (and head
             (cond
               ((fx<= #xd800 head #xdbff)
                (and (fx<= (fx+ slash 12) n)
                     (char=? (string-ref s (fx+ slash 6)) #\\)
                     (char=? (string-ref s (fx+ slash 7)) #\u)
                     (let ((tail (hex-unit-at (fx+ slash 8))))
                       (and tail (fx<= #xdc00 tail #xdfff)
                            (cons (integer->char
                                    (fx+ #x10000 (fx* (fx- head #xd800) #x400)
                                         (fx- tail #xdc00)))
                                  (fx+ slash 12))))))
               ((fx<= #xdc00 head #xdfff) #f)
               (else (cons (integer->char head) (fx+ slash 6)))))))
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
             (let* ((escape (string-ref s (fx+ i 1)))
                    (unicode (and (char=? escape #\u)
                                  (unicode-at i)))
                    (decoded (if unicode (car unicode)
                     (case escape
                       ((#\" #\\ #\/) escape)
                       ((#\b) (integer->char 8))
                       ((#\f) (integer->char 12))
                       ((#\n) #\newline)
                       ((#\r) #\return)
                       ((#\t) #\tab)
                       (else #f))))
                    (next (if unicode (cdr unicode) (fx+ i 2))))
               (cond
                 ((not decoded) #f)
                 ((not out)
                  (call-with-values open-string-output-port
                    (lambda (port get) (loop i run port get))))
                 (else
                  (when (fx< run i) (put-string out s run (fx- i run)))
                  (put-char out decoded)
                  (loop next next out extract))))))
        (else (loop (fx+ i 1) run out extract))))))
