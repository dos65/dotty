class Foo(val i: Int) extends AnyVal
class Reflective extends reflect.Selectable

@main def run() =
  val reflective = new Reflective {
    def bar(foo: Foo) = foo.i
  }
  val x = Foo(2)
  val i = reflective.bar(x)
  println(s"HAHA $i")
