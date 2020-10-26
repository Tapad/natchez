package natchez.opentelemetry

import java.net.URI
import java.util

import cats.effect.{Resource, Sync}
import cats.syntax.all.{none, _}
import io.grpc.Context
import io.opentelemetry.trace.{Tracer, Span => OtSpan}
import io.opentelemetry.{OpenTelemetry => JOpenTelemetry}
import natchez.TraceValue.{BooleanValue, NumberValue, StringValue}
import natchez.{Kernel, Span, TraceValue}

import scala.jdk.CollectionConverters._

class OpenTelemetrySpan[F[_]: Sync](tracer: Tracer, otSpan: OtSpan)
    extends Span[F] {
  override def kernel: F[Kernel] = Sync[F].delay {
    val propagator = JOpenTelemetry.getPropagators.getTextMapPropagator
    val scope = tracer.withSpan(otSpan)
    try {
      def setter(m: java.util.HashMap[String, String],
                 k: String,
                 v: String): Unit = {
        m.put(k, v)
        ()
      }
      val map = new util.HashMap[String, String]()
      propagator.inject(Context.current(), map, setter)
      Kernel(map.asScala.toMap)
    } finally {
      scope.close()
    }
  }

  override def put(fields: (String, TraceValue)*): F[Unit] = {
    fields.toList.traverse_ {
      case (k, StringValue(v)) => Sync[F].delay(otSpan.setAttribute(k, v))
      case (k, NumberValue(v)) =>
        Sync[F].delay(otSpan.setAttribute(k, v.doubleValue()))
      case (k, BooleanValue(v)) => Sync[F].delay(otSpan.setAttribute(k, v))
    }
  }

  override def span(name: String): Resource[F, Span[F]] = {
    println(s"parent($name) = ${otSpan}")
    val acquire = Sync[F].delay {
      val scope = tracer.withSpan(otSpan)
      try {
        tracer
          .spanBuilder(name)
          .startSpan()
      } finally {
        scope.close()
      }
    }
    def release(span: OtSpan): F[Unit] = Sync[F].delay(span.end())

    Resource.make(acquire)(release).map(new OpenTelemetrySpan[F](tracer, _))
  }

  override def traceId: F[Option[String]] = Sync[F].delay {
    otSpan.getContext.getTraceIdAsHexString.some
  }
  override def traceUri: F[Option[URI]] = none[URI].pure[F]
}
