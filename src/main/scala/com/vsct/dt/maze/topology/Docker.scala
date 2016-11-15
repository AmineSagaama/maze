/*
 * Copyright (C) 2016 VSCT
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.vsct.dt.maze.topology

import java.io.{Closeable, IOException}
import java.net.{Proxy, ProxySelector, SocketAddress, URI}
import java.util

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.command.{CreateContainerCmd, CreateNetworkResponse, InspectContainerResponse, ListContainersCmd}
import com.github.dockerjava.api.model.Network.Ipam
import com.github.dockerjava.api.model.Network.Ipam.Config
import com.github.dockerjava.api.model._
import com.github.dockerjava.core.{DefaultDockerClientConfig, DockerClientBuilder, DockerClientConfig}
import com.github.dockerjava.jaxrs.JerseyDockerCmdExecFactory
import com.typesafe.scalalogging.LazyLogging
import com.vsct.dt.maze.core.Execution
import com.vsct.dt.maze.helpers

import collection.JavaConverters._
import scala.language.postfixOps
import scala.util.Try

object Docker extends LazyLogging {

  private val defaultProxySelector = ProxySelector.getDefault

  ProxySelector.setDefault(
    new ProxySelector {
      override def select(uri: URI): util.List[Proxy] = {
        val modified = if (uri.getScheme == "tcp") {
          new URI("http", uri.getUserInfo, uri.getHost, uri.getPort, uri.getPath, uri.getQuery, uri.getFragment)
        } else {
          uri
        }

        defaultProxySelector.select(modified)
      }

      override def connectFailed(uri: URI, socketAddress: SocketAddress, e: IOException): Unit =
        defaultProxySelector.connectFailed(uri, socketAddress, e)

    }
  )

  val lowerBoundPort: Int = System.getProperty("port.range.lower", "50000").toInt
  val upperBoundPort: Int = System.getProperty("port.range.upper", "59999").toInt

  def constructBinding(port: Int): PortBinding = PortBinding.parse(s"$lowerBoundPort-$upperBoundPort:$port")

  private def resolveDockerUri(): String =
    System.getProperty("DOCKER_HOST", Option(System.getenv("DOCKER_HOST")).getOrElse("unix:///var/run/docker.sock"))

  private def resolveTlsSupport(): Boolean =
    Option(System.getenv("DOCKER_TLS_VERIFY")).map(_ == "1").getOrElse(!resolveDockerUri().startsWith("unix"))

  private val configuration: DockerClientConfig = DefaultDockerClientConfig.createDefaultConfigBuilder().withApiVersion("1.22")
    .withDockerTlsVerify(resolveTlsSupport()).withDockerHost(resolveDockerUri()).build()

  private val dockerCmdExecFactory = {
    val readTimeout = 60000
    val connectTimeout: Integer = 60000
    val totalConnections: Integer = 10
    val connectionsPerHost: Integer = 10

    new JerseyDockerCmdExecFactory()
      .withReadTimeout(readTimeout)
      .withConnectTimeout(connectTimeout)
      .withMaxTotalConnections(totalConnections)
      .withMaxPerRouteConnections(connectionsPerHost)
  }

  var client: DockerClient = DockerClientBuilder.getInstance(configuration)
    .withDockerCmdExecFactory(dockerCmdExecFactory).build

  private val TcpHost = """^tcp://([^:]+).*""".r

  val host: DockerHost = resolveDockerUri() match {
    case TcpHost(ip) => DockerHost(nameOrIp = ip)
    case _ => DockerHost(nameOrIp = "localhost")
  }

  case class DockerContainer(id: String)

  case class DockerProcessExecution(returnCode: Int, lines: List[String]) extends Exception {
    override def getMessage: String = lines.mkString("\n")
  }

  case class DockerHost(nameOrIp: String)

  def prepareCreateContainer(img: String): CreateContainerCmd = {
    val image = if (img.contains(":")) img else img + ":latest"
    val images = client.listImagesCmd().withImageNameFilter(image).exec()
    if (!images.asScala.exists(_.getRepoTags.contains(image))) {
      client.pullImageCmd(image).exec(new WaitForCallbackResponse[PullResponseItem]()).get()
    }

    client.createContainerCmd(image)
      .withCapAdd(Capability.NET_ADMIN)
      .withPrivileged(true)
      .withDnsSearch("socrate.vsct.fr")
      .withNetworkMode(helpers.DockerNetwork.networkName)
  }

  def createAndStartContainer(command: CreateContainerCmd): String = {
    val id = command.exec().getId
    client.startContainerCmd(id).exec()
    id
  }

  def listContainers(): List[Container] = listContainers(client.listContainersCmd())

  def listContainers(cmd: ListContainersCmd): List[Container] = cmd.exec().asScala.toList

  def stopContainer(id: String): Unit = {
    client.stopContainerCmd(id).exec()
    client.waitContainerCmd(id).exec(new WaitForCallbackResponse[WaitResponse]())
  }

  def killContainer(id: String, signal: String = "KILL"): Unit = client.killContainerCmd(id).withSignal(signal).exec()

  def forceRemoveContainer(id: String): Unit = client.removeContainerCmd(id).withForce(true).withRemoveVolumes(true).exec()

  def executionOnContainer(id: String, commands: String*): Execution[Array[String]] = new Execution[Array[String]] {
    override def execute(): Try[Array[String]] = executionOnContainerInternal(id, commands: _*)

    override val label: String = s"Execution of ${commands.mkString(" ")} on container $id"
  }

  private def executionOnContainerInternal(id: String, commands: String*): Try[Array[String]] = {
    val result = Try {
      val cmdId = client.execCreateCmd(id)
        .withAttachStderr(true)
        .withAttachStdout(true)
        .withCmd(commands: _*)
        .exec().getId

      val execResult = client.execStartCmd(cmdId).exec(new LogAppender)

      var execStatus = client.inspectExecCmd(cmdId).exec()
      // Wait until execution finishes
      while (execStatus.isRunning) {
        sleep()
        execStatus = client.inspectExecCmd(cmdId).exec()
      }

      val lines = execResult.result.replaceAll("\r\n", "\n").split("\n")

      if (Option(execStatus.getExitCode).exists(_ != 0)) {
        logger.debug(s"${commands.mkString(" ")}had error: ${lines.mkString("\n")}")
        throw DockerProcessExecution(execStatus.getExitCode, lines.toList)
      }
      logger.debug(s"result of ${commands.mkString(" ")}:\n${lines.mkString("\n")}")
      lines
    }
    result
  }

  def getIp(containerId: String): String =
    containerInfo(containerId).getNetworkSettings.getNetworks.get(helpers.DockerNetwork.networkName).getIpAddress

  class LogAppender extends ResultCallback[Frame] {
    val builder = new StringBuilder
    var finished = false

    override def onError(throwable: Throwable): Unit = {
      builder.append("An error occurred").append(throwable.getMessage)
    }

    override def onComplete(): Unit = {
      finished = true
    }

    override def onStart(closeable: Closeable): Unit = {
      builder.clear()
    }

    override def onNext(frame: Frame): Unit = {
      builder.append(new String(frame.getPayload, "UTF-8"))
    }

    override def close(): Unit = {}

    def result: String = {
      while (!finished) {
        sleep()
      }
      builder.result()
    }
  }

  def containerInfo(id: String): InspectContainerResponse = {
    client.inspectContainerCmd(id).exec()
  }

  private class WaitForCallbackResponse[T] extends ResultCallback[T] {

    var value: List[T] = Nil
    var done = false

    override def onError(throwable: Throwable): Unit = {
      done = true
    }

    override def onComplete(): Unit = done = true

    override def onStart(closeable: Closeable): Unit = {}

    override def onNext(o: T): Unit = {
      value = o :: value
    }

    override def close(): Unit = {}

    def get(): List[T] = {
      while (!done) {
        sleep()
      }
      value
    }
  }

  def restartContainer(id: String): Unit = {
    client.stopContainerCmd(id).exec()
    client.waitContainerCmd(id).exec(new WaitForCallbackResponse[WaitResponse]())
    client.startContainerCmd(id).exec()
  }

  def startCreatedContainer(id: String): Unit = {
    client.startContainerCmd(id).exec()
  }

  def createNetwork(name: String, subnet: String): CreateNetworkResponse = {
    val ipManagement: Ipam = new Ipam()
    val ipManagementConfig = new Config()
    ipManagementConfig.withSubnet(subnet)
    ipManagement.withConfig(ipManagementConfig)
    client.createNetworkCmd().withName(name).withIpam(ipManagement).exec()
  }

  def deleteNetwork(name: String): Unit = {
    client.removeNetworkCmd(name).exec()
  }

  def executionCreateFileOnContainer(id: String, path: String, content: String): Execution[Array[String]] = {
    val timestamp = System.nanoTime()
    executionOnContainer(id, "/bin/sh", "-c", s"cat > '$path' <<-EOF-$timestamp\n$content\nEOF-$timestamp")
  }

  private def sleep() = {
    val pauseTime: Long = 10
    Thread.sleep(pauseTime)
  }

}

