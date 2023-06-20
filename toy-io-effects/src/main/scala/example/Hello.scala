package example

import scala.util._

sealed trait TIO[+A]:
  def flatMap[B](f: A => TIO[B]) : TIO[B] = TIO.FlatMap(this, f)

  def map[B](f: A => B) : TIO[B] = flatMap(a => TIO.succeed(f(a)))
  
  def recover[B >: A](f: Throwable => TIO[B]): TIO[B] = TIO.Recover(this, f)

object TIO:
  // Effect constructor
  case class Effect[+A](a: () => A) extends TIO[A]
  // Effect combinator
  case class FlatMap[A, B](tio: TIO[A], f: A => TIO[B]) extends TIO[B]
  
  case class Fail[A](e: Throwable) extends TIO[A] 
  case class Recover[A](tio: TIO[A], f: Throwable => TIO[A]) extends TIO[A]

  //constructor functions
  def succeed[A](a: A) : TIO[A] = Effect(() => a)
  def effect[A](a: => A) : TIO[A] = Effect(() => a)
  def fail[A](throwable: Throwable): TIO[A] = Fail(throwable)


trait Runtime:
  def unsafeRunSync[A](tio: TIO[A]): Try[A]

object Runtime extends Runtime:
  
  def unsafeRunSync[A](tio: TIO[A]): Try[A] = eval(tio)

  private def eval[A](tio: TIO[A]): Try[A] = tio match 
    case TIO.Effect(a) => Try(a())

    case TIO.Fail(t) => Failure(t)

    case TIO.Recover(tio, f) => eval(tio) match
      case Failure(e) => eval(f(e))
      case success => success

    case TIO.FlatMap(tio, f: (Any => TIO[Any])) => eval(tio) match 
      case Success(s) => eval(f(s))
      case Failure(e) => Failure(e)

object Console:
  def puts(s: => String)  = TIO.effect(println(s))

trait TIOApp:
  def run: TIO[Any]
  final def main(args: Array[String]): Unit = 
    Runtime.unsafeRunSync(run).get

object SequenceEffects extends TIOApp:
  def run =
    (
      for 
        _ <- Console.puts("running first effect")
        _ <- TIO.effect(throw new RuntimeException("effect error"))
        _ <- TIO.effect(println("running second effect"))
      yield ()
    ).recover { e =>
        Console.puts(s"recovered from failure: ${e.getClass.getName}")
    }
  
