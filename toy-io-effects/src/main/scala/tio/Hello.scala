package tio

import scala.util._
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.Await
import scala.concurrent.duration._
import java.util.TimerTask
import java.util.Timer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent._
import java.util.concurrent.Executors._
import tio.TIO.AsyncDoneCallback
import java.util.concurrent.atomic.AtomicReference
import java.time.Instant
import tio.TIO.foreachPar

sealed trait TIO[+A]:
  def flatMap[B](f: A => TIO[B]) : TIO[B] = TIO.FlatMap(this, f)

  def map[B](f: A => B) : TIO[B] = flatMap(a => TIO.succeed(f(a)))
  
  def recover[B >: A](f: Throwable => TIO[B]): TIO[B] = TIO.Recover(this, f)

  def fork(): TIO[Fiber[A]] = TIO.Fork(this)

  def *> [B](that: TIO[B]): TIO[B] = flatMap(_ => that)

object TIO:
  // Effect constructor
  case class Effect[+A](a: () => A) extends TIO[A]
  // Effect combinator
  case class FlatMap[A, B](tio: TIO[A], f: A => TIO[B]) extends TIO[B]
  
  case class Fail[A](e: Throwable) extends TIO[A] 
  case class Recover[A](tio: TIO[A], f: Throwable => TIO[A]) extends TIO[A]

  type AsyncDoneCallback[T] = Try[T] => Unit
  type AsyncTask[T] = AsyncDoneCallback[T] => Unit

  case class EffectAsync[+A](asyncTask: AsyncTask[A]) extends  TIO[A]

  case class Fork[A](tio: TIO[A]) extends TIO[Fiber[A]]
  case class Join[A](fiber: Fiber[A]) extends TIO[A]

  //constructor functions
  def succeed[A](a: A) : TIO[A] = Effect(() => a)
  def effect[A](a: => A) : TIO[A] = Effect(() => a)
  def fail[A](throwable: Throwable): TIO[A] = Fail(throwable)

  def effectAsync[A](asyncTask: AsyncTask[A]): TIO[A] = EffectAsync(asyncTask)

  def foreach[A, B](xs: Iterable[A])(f: A => TIO[B]) : TIO[Iterable[B]] = 
    xs.foldLeft(TIO.succeed(Vector.empty[B])){ (acc, curr) => 
      for
        soFar <- acc
        x <- f(curr)
      yield soFar :+ x
    } 

  def foreachPar[A, B](xs: Iterable[A])(f: A => TIO[B]): TIO[Iterable[B]] =
    foreach(xs)(x => f(x).fork()).flatMap(fibers => foreach(fibers)(_.join()))


trait Executor:
  final def submit(thunk: => Unit): Unit = submitRunnable(() => thunk)

  def submitRunnable(thunk: Runnable): Unit

object Executor:
  private val threadCounter = new AtomicInteger(0)
  private def nextThreadId = threadCounter.incrementAndGet()

  def fixed(threads: Int, namePrefix: String): Executor = 
    val executor = newFixedThreadPool(threads, nameDeamonThreads(namePrefix))
    thunk => executor.submit(thunk)

  def nameDeamonThreads(namePrefix: String): ThreadFactory = 
    thunk => 
      val thread = new Thread(thunk, s"$namePrefix-$nextThreadId")
      thread.setDaemon(true)
      thread.setUncaughtExceptionHandler((_, e) => e.printStackTrace())
      thread

trait Fiber[+A]:

  def join(): TIO[A] = TIO.Join(this)
  
  private[tio] def onDone(done: AsyncDoneCallback[Any]): Fiber[A]


trait Runtime:

  def unsafeRunAsync[A](tio: TIO[A])(callback: Try[A] => Unit): Unit 

  def unsafeRunSync[A](tio: TIO[A], timeout: Duration = Duration.Inf): Try[A] = 
    Await.ready(unsafeRunToFuture(tio), timeout).value.get

  def unsafeRunToFuture[A](tio: TIO[A]) : Future[A] = 
    val promise = Promise[A]()
    unsafeRunAsync(tio)(promise.tryComplete)
    promise.future

object Runtime extends Runtime:

  private val executor = Executor.fixed(16, "tio-default")
  
  override def unsafeRunAsync[A](tio: TIO[A])(callback: Try[A] => Unit): Unit = 
    new FiberRuntime(tio)
      .onDone(callback.asInstanceOf[AsyncDoneCallback[Any]])
      .start() 

  private class FiberRuntime(tio: TIO[Any]) extends Fiber[Any]:
    
    type Callbacks = Set[AsyncDoneCallback[Any]]

    private val joined = new AtomicReference[Callbacks](Set.empty)

    private val result = new AtomicReference[Option[Try[Any]]](None)

    def onDone(done: AsyncDoneCallback[Any]): FiberRuntime = 
      joined.updateAndGet(_ + done)
      result.get.foreach(done)
      this 

    private def fiberDone(res: Try[Any]) = 
      result.set(Some(res))
      joined.get.foreach(_(res))


    def start(): Unit = 
      eval(tio)(fiberDone)
    
    private def eval(tio: TIO[Any])(done: Try[Any] => Unit): Unit =

      executor.submit: 
        tio match 
          
          case TIO.Fork(tio) =>
            val fiber = new FiberRuntime(tio)
            fiber.start()
            done(Success(fiber))

          case TIO.Join(fiber) =>
            fiber.onDone(done)

          case TIO.Effect(a) => 
            done(Try(a()))

          case TIO.Fail(t) => 
            done(Failure(t))

          case TIO.EffectAsync(callback) => 
            callback(done)

          case TIO.Recover(tio, f) => 
            eval(tio) {
              case Failure(e) => eval(f(e))(done)
              case success => done(success)
            }

          case TIO.FlatMap(tio, f: (Any => TIO[Any])) => 
            eval(tio) {
              case Success(s) => eval(f(s))(done)
              case Failure(e) => done(Failure(e))
            }


object Console:
  def puts(s: => String)  = TIO.effect(println(s))

object Clock:
  private val timer = new Timer("TIO-Timer", true)

  def sleep[A](duration: Duration): TIO[Unit] =
    TIO.effectAsync {
      onComplete =>
        timer.schedule(new TimerTask {
          override def run(): Unit = 
            onComplete(Success(()))
        }, duration.toMillis)
    }

trait TIOApp:
  def run: TIO[Any]
  final def main(args: Array[String]): Unit = 
    Runtime.unsafeRunSync(run).get

object SequenceEffects extends TIOApp:
  def run =
    (
      for 
        _ <- Console.puts("running first effect")
        //_ <- TIO.effect(throw new RuntimeException("effect error"))
        _ <- Clock.sleep(2.seconds)
        _ <- TIO.effect(println("running second effect"))
      yield ()
    ).recover { e =>
        Console.puts(s"recovered from failure: ${e.getClass.getName}")
    }
  

object ForkJoin extends TIOApp {
  def run = {
    for 
      _ <- Console.puts("1")
      fiber1 <- (Clock.sleep(2.seconds) *> Console.puts("2") *> TIO.succeed(1)).fork()
      _ <- Console.puts("3")
      i <- fiber1.join()
      _ <- Console.puts(s"fiber1 done: $i")
    yield ()
  }
}

object ForeachParExample extends TIOApp {

  val numbers = 1 to 10
  val random = new Random()
  val sleepRandomTime = TIO.effect(random.nextInt(1000).millis)
                          .flatMap(t => Clock.sleep(t) *> TIO.succeed(t))
  def run = {
    for 
    _ <- Console.puts(s"[${Instant.now}] foreach:")
      _ <- TIO.foreach(numbers)(i => Console.puts(i.toString))
      _ <- Console.puts(s"[${Instant.now}] foreachPar")
      _ <- TIO.foreachPar(numbers){ i =>
              sleepRandomTime.flatMap(t => Console.puts(s"$i after $t"))
            }
      _ <- Console.puts(s"[${Instant.now}] foreachPar done")
    yield ()
  }
}