package promisewell

import com.googlecode.concurrentlinkedhashmap.{
  ConcurrentLinkedHashMap, EvictionListener
}
import scala.concurrent.{ Future, ExecutionContext, Promise }
import scala.concurrent.duration.FiniteDuration

trait Cache[K,V] {
  /** attempt to resolve element `k` from the cache */
  def get(k: K): Option[Future[V]]

  /** attempt to resolve element `k` from the cache, falling back on making
   *  a new future, caching it's result in the process */
  def apply(k: K, make: () => Future[V])
    (implicit ec: ExecutionContext): Future[V]

  /** remove element `k` from the cache */
  def remove(k: K): Option[Future[V]]

  /** clear the contents of the cache */
  def clear(): Unit

  /** Register a function to be invoked when a cached item
   *  is evicted from the cache returning a _new_ cache. This cache
   *  will _not_ share items cached with previous cache. */
  def onEviction(ev: (K, Future[V]) => Unit): Cache[K, V]
}

object Cache {
  object Default {
    val InitCap = 16
    val MaxCap = Long.MaxValue - Int.MaxValue
  }

  /** returns a cache whose items will be limited in number to that
   *  of the specified maxCap. */
  def capped[K,V](
    initCap: Int = Default.InitCap,
    maxCap: Long = Default.MaxCap): Cache[K, V] =
      Capped(initCap, maxCap)

  /** returns a cache whose items will expire after the specified ttl and
   *  that haven't been accessed after the specified ttidle. This cache
   *  will also be limited in number based on the specified maxCap */
  def lru[K,V](
    initCap: Int = Default.InitCap,
    maxCap: Long = Default.MaxCap,
    ttl: FiniteDuration,
    ttidle: FiniteDuration): Cache[K, V] =
      Lru(initCap, maxCap, ttl, ttidle)

  private [promisewell] def newBuilder[A,B]
   (initCap: Int, maxCap: Long) = {
    new ConcurrentLinkedHashMap.Builder[A, B]
      .initialCapacity(initCap)
      .maximumWeightedCapacity(maxCap)
   }
}

case class Capped[K,V](
  initCapacity: Int,
  maxCapacity: Long,
  evictions: Option[(K,Future[V]) => Unit] = None
) extends Cache[K, V] {

  private[this] val underlying = {
    val b = Cache.newBuilder[K, Future[V]](initCapacity, maxCapacity)
    evictions.foreach { ev =>
      b.listener(new EvictionListener[K, Future[V]] {
        def onEviction(k: K, v: Future[V]) =
          ev(k, v)
      })
    }
    b.build
  }

  def onEviction(ev: (K, Future[V]) => Unit) =
    copy(evictions = Some(ev))

  def get(k: K): Option[Future[V]] = Option(underlying.get(k))

  def apply
   (k: K, make: () => Future[V])
   (implicit ec: ExecutionContext): Future[V] = {
    val promise = Promise[V]()
    underlying.putIfAbsent(k, promise.future) match {
      case null =>
        val future = make()
        future.onComplete { value =>
          promise.complete(value)
          if (value.isFailure) underlying.remove(k)
        }
        future
      case made => made
    }
  }

  def remove(k: K): Option[Future[V]] = Option(underlying.remove(k))

  def clear(): Unit = underlying.clear()
}

case class Lru[K, V](
  initCapacity: Int,
  maxCapacity: Long,
  ttlive: FiniteDuration,
  ttidle: FiniteDuration,
  evictions: Option[(K, Future[V]) => Unit] = None
) extends Cache[K, V] {

  private[this] case class Entry(promise: Promise[V]) {
    private[this] val created = System.currentTimeMillis
    @volatile private[this] var touched = created
    def future = promise.future
    def touch() = touched = System.currentTimeMillis
    def live = {
      val now = System.currentTimeMillis
      (created +  ttlive.toMillis) > now && (touched + ttidle.toMillis) > now
    }
  }

  private[this] val underlying = {
    val b = Cache.newBuilder[K, Entry](initCapacity, maxCapacity)
    evictions.foreach { ev =>
      b.listener(new EvictionListener[K, Entry] {
        def onEviction(k: K, v: Entry) = ev(k, v.future)
      })
    }
    b.build
  }

  def onEviction(ev: (K, Future[V]) => Unit) =
    copy(evictions = Some(ev))

  def get(k: K): Option[Future[V]] =
    underlying.get(k) match {
      case null => None
      case entry if entry.live =>
        entry.touch()
        Some(entry.future)
      case entry =>
        // expire
        if (underlying.remove(k, entry)) None
        else get(k)
    }

  def apply
   (k: K, make: () => Future[V])
   (implicit ec: ExecutionContext): Future[V] = {
    def put() = {
      val newEntry = Entry(Promise[V]())
      val future =
        underlying.put(k, newEntry) match {
          case null => make()
          case entry =>
            if (entry.live) entry.future
            else make()
        }
      future.onComplete { value =>
        newEntry.promise.tryComplete(value)
        if (value.isFailure) underlying.remove(k, newEntry)
      }
      newEntry.future
    }
    underlying.get(k) match {
      case null => put()        
      case entry if entry.live =>
        entry.touch()
        entry.future
      case entry => put()
    }
  }

  def remove(k: K): Option[Future[V]] =
    underlying.remove(k) match {
      case null => None
      case entry if (entry.live) => Some(entry.future)
      case _ => None
    }

  def clear(): Unit = underlying.clear()
}
