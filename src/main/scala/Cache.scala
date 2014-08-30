package promisewell

import com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap
import scala.concurrent.{ Future, ExecutionContext, Promise }
import scala.concurrent.duration.FiniteDuration

trait Cache[K, V] {
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
}

object Cache {
  def capped[K,V](
    cap: Long,
    initCap: Int): Cache[K, V] =
    Capped(cap, initCap)

  def lru[K,V](
    cap: Long,
    initCap: Int,
    ttl: FiniteDuration,
    ttidle: FiniteDuration): Cache[K, V] =
      Lru(cap, initCap, ttl, ttidle)
}

case class Capped[K, V](
  capacity: Long,
  initCapacity: Int
) extends Cache[K, V] {

  private[this] val underlying =
    new ConcurrentLinkedHashMap.Builder[K, Future[V]]
      .initialCapacity(initCapacity)
      .maximumWeightedCapacity(capacity)
      .build

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
  capacity: Long,
  initCapacity: Int,
  ttlive: FiniteDuration,
  ttidle: FiniteDuration
) extends Cache[K, V]{

  private[this] case class Entry[V](promise: Promise[V]) {
    val created = System.currentTimeMillis
    @volatile var touched = created
    def future = promise.future
    def touch() = touched = System.currentTimeMillis
    def live = {
      val now = System.currentTimeMillis
      (created +  ttlive.toMillis) > now && (touched + ttidle.toMillis) > now
    }
  }

  private[this] val underlying =
    new ConcurrentLinkedHashMap.Builder[K, Entry[V]]
      .initialCapacity(initCapacity)
      .maximumWeightedCapacity(capacity)
      .build

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
