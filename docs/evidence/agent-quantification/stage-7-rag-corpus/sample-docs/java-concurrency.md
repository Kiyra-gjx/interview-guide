# Java 并发面试知识

## 基础模型

Java 并发问题通常从线程状态、内存可见性、原子性和有序性展开。线程状态包括 NEW、RUNNABLE、BLOCKED、WAITING、TIMED_WAITING 和 TERMINATED。面试回答时不要只背状态名，要说明状态切换原因，例如进入 synchronized 竞争失败会进入 BLOCKED，调用 Object.wait 会释放监视器并进入 WAITING，调用 Thread.sleep 不释放锁并进入 TIMED_WAITING。

Java 内存模型关注主内存和工作内存之间的同步规则。共享变量被多个线程读写时，如果没有 happens-before 关系，线程可能读到旧值。常见 happens-before 包括程序次序规则、监视器锁释放先行发生于后续加锁、volatile 写先行发生于后续 volatile 读、线程 start 先行发生于线程内动作、线程内动作先行发生于 join 返回。

## synchronized 与 ReentrantLock

synchronized 是 JVM 内置监视器锁，进入和退出同步块由字节码 monitorenter 和 monitorexit 表示。它的优点是语义简单、异常时自动释放锁、JVM 能做锁消除和锁粗化。面试中应说明 synchronized 保证互斥，也通过锁释放和获取建立 happens-before，从而保证可见性。

ReentrantLock 是 JUC 显式锁，需要在 finally 中释放。它支持公平锁、非公平锁、可中断获取、限时获取和多个 Condition 队列。回答取舍时可以说：普通互斥优先 synchronized；需要 tryLock、lockInterruptibly 或多个等待队列时用 ReentrantLock。不要把 ReentrantLock 简单说成性能更好，现代 JVM 下性能差异不是主要决策点。

## volatile 与原子类

volatile 适合一写多读的状态标记、配置开关和双重检查锁定中的引用发布。volatile 能保证可见性和禁止相关重排序，但不能保证复合操作原子性。`count++` 包含读、加一、写回三个步骤，使用 volatile 仍然可能丢失更新。

AtomicInteger、AtomicLong 和 AtomicReference 通过 CAS 实现无锁原子更新。CAS 需要比较内存旧值，如果匹配才写新值。它避免阻塞，但在高竞争下会自旋重试，可能浪费 CPU。ABA 问题可以用版本号或 AtomicStampedReference 处理。回答时要把“无锁”解释为不使用阻塞锁，而不是没有同步成本。

## 线程池

ThreadPoolExecutor 的关键参数包括 corePoolSize、maximumPoolSize、keepAliveTime、workQueue、threadFactory 和 rejectedExecutionHandler。任务提交时先创建核心线程，核心线程满后进入队列，队列满后创建非核心线程，达到最大线程数后触发拒绝策略。

生产环境不建议直接使用 Executors.newFixedThreadPool 或 newCachedThreadPool，因为默认队列或线程数可能无界。更稳妥的方式是显式设置线程池参数、命名线程、配置有界队列和拒绝策略，并监控队列长度、活跃线程数、拒绝次数和任务耗时。CPU 密集型任务线程数通常接近 CPU 核数，IO 密集型任务可适当增大，但要结合下游容量。

## 常见追问

死锁通常需要同时满足互斥、请求保持、不可剥夺和循环等待四个条件。排查时可以用 jstack、线程 dump 或监控平台查看 BLOCKED 线程和持有锁关系。避免死锁的常见方式包括固定加锁顺序、缩小锁粒度、使用 tryLock 超时回退和避免在持锁期间调用外部服务。

并发面试回答应优先给出场景、机制、风险和工程取舍。例如“为什么不用 synchronizedMap”时，可以说明它对整张 Map 加粗粒度锁，迭代时仍需要外部同步；ConcurrentHashMap 在 JDK 8 通过 CAS 和 synchronized 锁桶头节点降低锁粒度，读操作大多无锁，更适合高并发读写场景。
