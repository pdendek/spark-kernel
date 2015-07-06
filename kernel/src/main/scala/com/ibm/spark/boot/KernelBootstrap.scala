/*
 * Copyright 2014 IBM Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ibm.spark.boot

import akka.actor.{ActorRef, ActorSystem}
import com.ibm.spark.boot.layer._
import com.ibm.spark.interpreter.Interpreter
import com.ibm.spark.kernel.api.KernelLike
import com.ibm.spark.kernel.protocol.v5.KernelStatusType._
import com.ibm.spark.kernel.protocol.v5._
import com.ibm.spark.kernel.protocol.v5.kernel.ActorLoader
import com.ibm.spark.magic.MagicLoader
import com.ibm.spark.security.KernelSecurityManager
import com.ibm.spark.utils.LogLike
import com.typesafe.config.Config
import org.apache.spark.SparkContext
import org.apache.spark.sql.SQLContext
import org.zeromq.ZMQ

object KernelBootstrap {
  def standardKernelBootstrap(config: Config): KernelBootstrap =
    new KernelBootstrap(config)
      with StandardBareInitialization
      with StandardComponentInitialization
      with StandardHandlerInitialization
      with StandardHookInitialization

  def standardKernelBootstrap(): KernelBootstrap =
    standardKernelBootstrap(CommandLineOptions.empty.toConfig)
}

class KernelBootstrap(private val config: Config) extends LogLike {
  this: BareInitialization with ComponentInitialization
    with HandlerInitialization with HookInitialization =>

  private val DefaultAppName                    = SparkKernelInfo.banner
  private val DefaultActorSystemName            = "spark-kernel-actor-system"

  private var actorSystem: ActorSystem          = _
  private var actorLoader: ActorLoader          = _
  private var kernelMessageRelayActor: ActorRef = _
  private var statusDispatch: ActorRef          = _

  private var kernel: KernelLike                = _
  private var sparkContext: SparkContext        = _
  private var sqlContext: SQLContext            = _
  private var interpreters: Seq[Interpreter]    = Nil
  private var magicLoader: MagicLoader          = _

  /**
   * Returns the Kernel API instance created during bootstrapping.
   *
   * @return The kernel API instance
   */
  def getKernel: KernelLike = kernel

  /**
   * Returns the Spark Context created during bootstrapping.
   *
   * @return The Spark Context
   */
  def getSparkContext: SparkContext = sparkContext

  /**
   * Returns the Spark SQL Context created during bootstrapping.
   *
   * @return The Spark SQL Context
   */
  def getSqlContext: SQLContext = sqlContext

  /**
   * Returns all interpreters created during the bootstrapping.
   *
   * @return The collection of interpreters
   */
  def getInterpreters: Seq[Interpreter] = interpreters

  /**
   * Returns the magic loader created during bootstrapping.
   *
   * @return The magic loader
   */
  def getMagicLoader: MagicLoader = magicLoader

  /**
   * Initializes all kernel systems.
   */
  def initialize() = {
    // TODO: Investigate potential to initialize System out/err/in to capture
    //       Console DynamicVariable initialization (since takes System fields)
    //       and redirect it to a workable location (like an actor) with the
    //       thread's current information attached
    //
    // E.G. System.setOut(customPrintStream) ... all new threads will have
    //      customPrintStream as their initial Console.out value
    //

    displayVersionInfo()

    // Initialize the bare minimum to report a starting message
    val (actorSystem, actorLoader, kernelMessageRelayActor, statusDispatch) =
      initializeBare(
        config = config,
        actorSystemName = DefaultActorSystemName
      )
    this.actorSystem = actorSystem
    this.actorLoader = actorLoader
    this.kernelMessageRelayActor = kernelMessageRelayActor
    this.statusDispatch = statusDispatch

    // Indicate that the kernel is now starting
    publishStatus(KernelStatusType.Starting)

    // Initialize components needed elsewhere
    val (commStorage, commRegistrar, commManager, interpreter,
      kernel, sparkContext, sqlContext, dependencyDownloader,
      magicLoader, responseMap) =
      initializeComponents(
        config      = config,
        appName     = DefaultAppName,
        actorLoader = actorLoader
      )
    this.kernel = kernel
    this.sparkContext = sparkContext
    this.sqlContext = sqlContext
    this.interpreters ++= Seq(interpreter)
    this.magicLoader = magicLoader

    // Initialize our handlers that take care of processing messages
    initializeHandlers(
      config        = config,
      actorSystem   = actorSystem,
      actorLoader   = actorLoader,
      interpreter   = interpreter,
      commRegistrar = commRegistrar,
      commStorage   = commStorage,
      magicLoader   = magicLoader,
      responseMap   = responseMap
    )

    // Initialize our hooks that handle various JVM events
    initializeHooks(
      interpreter = interpreter
    )

    logger.debug("Initializing security manager")
    System.setSecurityManager(new KernelSecurityManager)

    logger.info("Marking relay as ready for receiving messages")
    kernelMessageRelayActor ! true

    this
  }

  /**
   * Shuts down all kernel systems.
   */
  def shutdown() = {
    logger.info("Shutting down Spark Context")
    sparkContext.stop()

    logger.info("Shutting down interpreters")
    interpreters.foreach(_.stop())

    logger.info("Shutting down actor system")
    actorSystem.shutdown()

    this
  }

  /**
   * Waits for the main actor system to terminate.
   */
  def waitForTermination() = {
    logger.debug("Waiting for actor system to terminate")
    actorSystem.awaitTermination()

    this
  }

  private def publishStatus(
    status: KernelStatusType,
    parentHeader: Option[ParentHeader] = None
  ): Unit = {
    parentHeader match {
      case Some(header) => statusDispatch ! ((status, header))
      case None         => statusDispatch ! status
    }
  }

  @inline private def displayVersionInfo() = {
    logger.info("Kernel version: " + SparkKernelInfo.implementationVersion)
    logger.info("Scala version: " + SparkKernelInfo.languageVersion)
    logger.info("ZeroMQ (JeroMQ) version: " + ZMQ.getVersionString)
  }
}

