package cbt
import java.io._
import java.nio.file.Files.readAllBytes
import java.net._
import org.eclipse.jgit.api._
import org.eclipse.jgit.internal.storage.file._
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.lib.Ref

object GitDependency{
  val GitUrl = "(git:|https:|file:/)//([^/]+)/(.+)".r
  def apply(
    url: String, ref: String, subDirectory: Option[String] = None, // example: git://github.com/cvogt/cbt.git#<some-hash>
    pathToNestedBuild: Seq[String] = Seq()
  )(implicit context: Context ): BuildInterface = {
    // TODO: add support for authentication via ssh and/or https
    // See http://www.codeaffine.com/2014/12/09/jgit-authentication/
    val GitUrl( _, domain, path ) = url
    val credentialsFile = context.workingDirectory ++ "/git.login"
    def authenticate(_git: CloneCommand) =
      if(!credentialsFile.exists){
        _git
      } else {
        val (user, password) = {
          // TODO: implement safer method than reading credentials from plain text file
          val c = new String(readAllBytes(credentialsFile.toPath)).split("\n").head.trim.split(":")
          (c(0), c.drop(1).mkString(":"))
        }
        _git.setCredentialsProvider( new UsernamePasswordCredentialsProvider(user, password) )
      }

    val logger = context.logger

    def moduleKey = (
      this.getClass.getName
      ++ "(" ++ url ++ subDirectory.map("/" ++ _).getOrElse("") ++ "#" ++ ref
      ++ ", "
      ++ pathToNestedBuild.mkString(", ")
      ++ ")"
    )

    val taskCache = new PerClassCache(context.transientCache, moduleKey)(logger)

    def checkout: File = taskCache[Dependency]("checkout").memoize{
      val checkoutDirectory = context.cache ++ s"/git/$domain/$path/$ref"
      val _git = if(checkoutDirectory.exists){
        logger.git(s"Found existing checkout of $url#$ref in $checkoutDirectory")
        val _git = new Git(new FileRepository(checkoutDirectory ++ "/.git"))
        val actualRef = _git.getRepository.getBranch
        if(actualRef != ref){
          logger.git(s"actual ref '$actualRef' does not match expected ref '$ref' - fetching and checking out")
          _git.fetch().call()
          _git.checkout().setName(ref).call
        }
        _git
      } else {
        logger.git(s"Cloning $url into $checkoutDirectory")
        val _git = authenticate(
          Git
            .cloneRepository()
            .setURI(url)
            .setDirectory(checkoutDirectory)
        ).call()

        logger.git(s"Checking out ref $ref")
        _git.checkout().setName(ref).call()
        _git
      }
      val actualRef = _git.getRepository.getBranch
      assert( actualRef == ref, s"actual ref '$actualRef' does not match expected ref '$ref'")
      checkoutDirectory
    }

    DirectoryDependency(
      context.copy(
        workingDirectory = checkout ++ subDirectory.map("/" ++ _).getOrElse("")
      ),
      pathToNestedBuild: _*
    )
  }
}
