/* Place intermediate workflow results in a subdirectory.
 *
 * All the workflow variables are passed through, so that the workflow
 * will complete only after file movements are complete.
 */
package dxWDL.runner

// DX bindings
import com.dnanexus.{DXAnalysis, DXAPI, DXContainer, DXDataObject,
    DXEnvironment, DXJSON, DXFile, DXProject, DXSearch}
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dxWDL._
import dxWDL.WdlVarLinks.yaml
import net.jcazevedo.moultingyaml._
import scala.collection.JavaConverters._
import spray.json._
import wdl.draft2.model.{WdlNamespaceWithWorkflow}


case class WorkflowOutputReorg(verbose: Boolean) {

    // Efficiently get the names of many files. We
    // don't want to do a `describe` each one of them, instead,
    // we do a bulk-describe.
    //
    // In other words, this code is an efficient replacement for:
    // files.map(_.describe().getName())
    def bulkGetFilenames(files: Seq[DXFile], dxProject: DXProject) : Vector[String] = {
        val info:List[DXDataObject] = DXSearch.findDataObjects()
            .withIdsIn(files.asJava)
            .inProject(dxProject)
            .includeDescribeOutput()
            .execute().asList().asScala.toList
        info.map(_.getCachedDescribe().getName()).toVector
    }

    // find all output files from the analysis
    //
    // We want to find all inputs and outputs with little platform overhead.
    // Here, we use a describe API call on the analysis.
    //
    // The output files could include some of the inputs, and we need
    // to filter those out. One way, is to check file creation dates,
    // however, that would require a describe API call per output
    // file. Instead, we find all the output files that do not also
    // appear in the input.
    def analysisFileOutputs(dxProject: DXProject,
                            dxAnalysis: DXAnalysis) : Vector[DXFile]= {
        val req: ObjectNode = DXJSON.getObjectBuilder()
            .put("fields", DXJSON.getObjectBuilder()
                     .put("input", true)
                     .put("output", true)
                     .build()).build()
        val rep = DXAPI.analysisDescribe(dxAnalysis.getId(), req, classOf[JsonNode])
        val repJs:JsValue = Utils.jsValueOfJsonNode(rep)
        val outputs = repJs.asJsObject.fields.get("output") match {
            case None => throw new Exception("Failed to get analysis outputs")
            case Some(x) => x
        }
        val inputs = repJs.asJsObject.fields.get("input") match {
            case None => throw new Exception("Failed to get analysis inputs")
            case Some(x) => x
        }

        val fileOutputs : Set[DXFile] = WdlVarLinks.findDxFiles(outputs).toSet
        val fileInputs: Set[DXFile] = WdlVarLinks.findDxFiles(inputs).toSet
        val realOutputs:Set[DXFile] = fileOutputs.toSet -- fileInputs.toSet
        Utils.appletLog(verbose, s"analysis has ${fileOutputs.size} output files")
        Utils.appletLog(verbose, s"analysis has ${fileInputs.size} input files")
        Utils.appletLog(verbose, s"analysis has ${realOutputs.size} real outputs")

        Utils.appletLog(verbose, "Checking timestamps")
        if (realOutputs.size > Utils.MAX_NUM_FILES_MOVE_LIMIT) {
            Utils.appletLog(verbose, s"WARNING: Large number of outputs (${realOutputs.size}), not moving objects")
            return Vector.empty
        }
        val anlCreateTs:java.util.Date = dxAnalysis.describe.getCreationDate()
        val realFreshOutputs:List[DXDataObject] = DXSearch.findDataObjects()
            .withIdsIn(realOutputs.asJava)
            .inProject(dxProject)
            .createdAfter(anlCreateTs)
            .execute().asList().asScala.toList
        val outputFiles: Vector[DXFile] = realFreshOutputs.map(
            dxObj => DXFile.getInstance(dxObj.getId())
        ).toVector
        Utils.appletLog(verbose, s"analysis has ${outputFiles.length} verified output files")

        outputFiles
    }

    // Move all intermediate results to a sub-folder
    def moveIntermediateResultFiles(exportFiles: Vector[DXFile]): Unit = {
        val dxEnv = DXEnvironment.create()
        val dxProject = dxEnv.getProjectContext()
        val dxProjDesc = dxProject.describe
        val dxAnalysis = dxEnv.getJob.describe.getAnalysis
        val outFolder = dxAnalysis.describe.getFolder
        val intermFolder = outFolder + "/" + Utils.INTERMEDIATE_RESULTS_FOLDER
        Utils.appletLog(verbose, s"proj=${dxProjDesc.getName} outFolder=${outFolder}")

        // find all analysis output files
        val analysisFiles: Vector[DXFile] = analysisFileOutputs(dxProject, dxAnalysis)
        if (analysisFiles.isEmpty)
            return

        val exportIds:Set[String] = exportFiles.map(_.getId).toSet
        val exportNames:Seq[String] = bulkGetFilenames(exportFiles, dxProject)
        Utils.appletLog(verbose, s"exportFiles=${exportNames}")

        // Figure out which of the files should be kept
        val intermediateFiles = analysisFiles.filter(x => !(exportIds contains x.getId))
        val iNames:Seq[String] = bulkGetFilenames(intermediateFiles, dxProject)
        Utils.appletLog(verbose, s"intermediate files=${iNames}")
        if (intermediateFiles.isEmpty)
            return

        // Move all non exported results to the subdir. Do this in
        // a single API call, to improve performance.
        val folderContents:DXContainer.FolderContents = dxProject.listFolder(outFolder)
        val subFolders: List[String] = folderContents.getSubfolders().asScala.toList
        Utils.appletLog(verbose, s"subfolders=${subFolders}")
        if (!(subFolders contains intermFolder)) {
            Utils.appletLog(verbose, s"Creating intermediate results sub-folder ${intermFolder}")
            dxProject.newFolder(intermFolder)
        } else {
            Utils.appletLog(verbose, s"Intermediate results sub-folder ${intermFolder} already exists")
        }
        dxProject.moveObjects(intermediateFiles.asJava, intermFolder)
    }

    // Convert the environment to yaml, and then pretty
    // print it.
    def prettyPrint(inputs: Map[String, WdlVarLinks]) : String = {
        val m: Map[YamlValue, YamlValue] = inputs.map{
            case (key, wvl) => YamlString(key) -> yaml(wvl)
        }.toMap
        YamlObject(m).print()
    }

    def apply(nswf: WdlNamespaceWithWorkflow,
              wfOutputs: Map[String, WdlVarLinks]) : Map[String, JsValue] = {
        Utils.appletLog(verbose, s"Initial inputs=\n${prettyPrint(wfOutputs)}")

        // Figure out the output variables from the workflow inputs
        val wfOutputFiles: Vector[DXFile] = wfOutputs.map{ case (_, wvl) =>
            WdlVarLinks.findDxFiles(wvl)
        }.toVector.flatten

        // Reorganize directory structure
        moveIntermediateResultFiles(wfOutputFiles)

        // empty results
        Map.empty[String, JsValue]
    }
}
