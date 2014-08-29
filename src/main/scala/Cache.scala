package promisewell

import scala.concurrent.{ Future, ExecutionContext, Promise }
import scala.concurrent.duration.FiniteDuration
import java.util.{ Collections, LinkedHashMap }

trait Cache[K, V] {
  def get(k: K): Option[Future[V]]
  def apply(k: K, make: () => Future[V])
    (implicit ec: ExecutionContext): Future[V]
  def remove(k: K): Option[Future[V]]
  def clear(): Unit
}

object Cache {
  def capped[K,V](cap: Long, initCap: Int): Cache[K, V] =
    Capped(cap, initCap)
  def lru[K,V](
    cap: Long, initCap: Int, ttl: FiniteDuration, ttidle: FiniteDuration): Cache[K, V] =
    Lru(cap, initCap, ttl, ttidle)
}

case class Capped[K, V](
  capacity: Long,
  initCapacity: Int
) extends Cache[K, V] {

  private[this] val underlying =
    Collections.synchronizedMap(
      new LinkedHashMap[K, Future[V]](initCapacity))

  def get(k: K): Option[Future[V]] = Option(underlying.get(k))

  def apply(k: K, make: () => Future[V])
   (implicit ec: ExecutionContext): Future[V] = {
    val promise = Promise[V]()
    underlying.put(k, promise.future) match {
      case null =>
        val future = make()
        future.onComplete { value =>
          promise.complete(value)
          if (value.isFailure) underlying.remove(k)
        }
        future
      case existingFuture => existingFuture
    }
  }

  def remove(k: K) = Option(underlying.remove(k))

  def clear() = underlying.clear()
}

case class Lru[K, V](
  capcity: Long,
  initCapacity: Int,
  ttlive: FiniteDuration,
  ttidle: FiniteDuration
) extends Cache[K, V]{

  private[this] case class Entry[V](promise: Promise[V]) {
    val created = System.currentTimeMillis
    @volatile var touched = created
    def future = promise.future
    def touch() = touched = System.currentTimeMillis
    def live = true // todo base on config above
  }

  private[this] val underlying =
    Collections.synchronizedMap(
      new LinkedHashMap[K, Entry[V]](initCapacity))

  def get(k: K): Option[Future[V]] =
    underlying.get(k) match {
      case null => None
      case entry if entry.live =>
        entry.touch()
        Some(entry.future)
      case entry =>
        // expire
        underlying.remove(k, entry) match {
          case null => None
          case _ => get(k)
        }
    }

  def apply(k: K, make: () => Future[V])
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
