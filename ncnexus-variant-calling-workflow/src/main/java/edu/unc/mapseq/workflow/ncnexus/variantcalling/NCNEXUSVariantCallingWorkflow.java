package edu.unc.mapseq.workflow.ncnexus.variantcalling;

import java.io.File;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.commons.lang.StringUtils;
import org.jgrapht.DirectedGraph;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.renci.jlrm.condor.CondorJob;
import org.renci.jlrm.condor.CondorJobBuilder;
import org.renci.jlrm.condor.CondorJobEdge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.unc.mapseq.commons.ncnexus.variantcalling.SaveDepthOfCoverageAttributesRunnable;
import edu.unc.mapseq.dao.MaPSeqDAOException;
import edu.unc.mapseq.dao.model.Attribute;
import edu.unc.mapseq.dao.model.FileData;
import edu.unc.mapseq.dao.model.Flowcell;
import edu.unc.mapseq.dao.model.MimeType;
import edu.unc.mapseq.dao.model.Sample;
import edu.unc.mapseq.dao.model.Workflow;
import edu.unc.mapseq.dao.model.WorkflowRunAttempt;
import edu.unc.mapseq.module.gatk.GATKDownsamplingType;
import edu.unc.mapseq.module.gatk.GATKPhoneHomeType;
import edu.unc.mapseq.module.gatk2.GATKDepthOfCoverageCLI;
import edu.unc.mapseq.module.gatk2.GATKUnifiedGenotyperCLI;
import edu.unc.mapseq.module.picard.PicardMarkDuplicates;
import edu.unc.mapseq.workflow.WorkflowException;
import edu.unc.mapseq.workflow.impl.AbstractSampleWorkflow;
import edu.unc.mapseq.workflow.impl.WorkflowJobFactory;
import edu.unc.mapseq.workflow.impl.WorkflowUtil;

public class NCNEXUSVariantCallingWorkflow extends AbstractSampleWorkflow {

    private final Logger logger = LoggerFactory.getLogger(NCNEXUSVariantCallingWorkflow.class);

    public NCNEXUSVariantCallingWorkflow() {
        super();
    }

    @Override
    public String getName() {
        return NCNEXUSVariantCallingWorkflow.class.getSimpleName().replace("Workflow", "");
    }

    @Override
    public String getVersion() {
        ResourceBundle bundle = ResourceBundle.getBundle("edu/unc/mapseq/workflow/ncnexus/variantcalling/workflow");
        String version = bundle.getString("version");
        return StringUtils.isNotEmpty(version) ? version : "0.0.1-SNAPSHOT";
    }

    @Override
    public Graph<CondorJob, CondorJobEdge> createGraph() throws WorkflowException {
        logger.info("ENTERING createGraph()");

        DirectedGraph<CondorJob, CondorJobEdge> graph = new DefaultDirectedGraph<CondorJob, CondorJobEdge>(
                CondorJobEdge.class);

        int count = 0;

        String siteName = getWorkflowBeanService().getAttributes().get("siteName");
        String referenceSequence = getWorkflowBeanService().getAttributes().get("referenceSequence");
        String depthOfCoverageIntervalList = getWorkflowBeanService().getAttributes()
                .get("depthOfCoverageIntervalList");
        String unifiedGenotyperIntervalList = getWorkflowBeanService().getAttributes().get(
                "unifiedGenotyperIntervalList");
        String unifiedGenotyperDBSNP = getWorkflowBeanService().getAttributes().get("unifiedGenotyperDBSNP");
        String GATKKey = getWorkflowBeanService().getAttributes().get("GATKKey");
        String subjectMergeHome = getWorkflowBeanService().getAttributes().get("subjectMergeHome");
        File subjectMergeDirectory = new File(subjectMergeHome, "subject-merge");

        Set<Sample> sampleSet = getAggregatedSamples();
        logger.info("sampleSet.size(): {}", sampleSet.size());

        Workflow mergeBAMWorkflow = null;
        try {
            mergeBAMWorkflow = getWorkflowBeanService().getMaPSeqDAOBean().getWorkflowDAO()
                    .findByName("NCNEXUSMergeBAM").get(0);
        } catch (MaPSeqDAOException e1) {
            e1.printStackTrace();
        }

        WorkflowRunAttempt attempt = getWorkflowRunAttempt();

        for (Sample sample : sampleSet) {

            if ("Undetermined".equals(sample.getBarcode())) {
                continue;
            }

            logger.info(sample.toString());

            String subjectName = "";
            Set<Attribute> sampleAttributes = sample.getAttributes();
            if (sampleAttributes != null && !sampleAttributes.isEmpty()) {
                for (Attribute attribute : sampleAttributes) {
                    if (attribute.getName().equals("subjectName")) {
                        subjectName = attribute.getValue();
                        break;
                    }
                }
            }

            if (StringUtils.isEmpty(subjectName)) {
                throw new WorkflowException("subjectName is empty");
            }

            File subjectDirectory = new File(subjectMergeDirectory, subjectName);
            logger.info("subjectDirectory.getAbsolutePath(): {}", subjectDirectory.getAbsolutePath());
            if (!subjectDirectory.exists()) {
                logger.error("subjectDirectory doesn't exist");
                throw new WorkflowException("subjectDirectory doesn't exist");
            }

            Flowcell flowcell = sample.getFlowcell();
            File outputDirectory = new File(sample.getOutputDirectory(), getName());
            File tmpDirectory = new File(outputDirectory, "tmp");
            tmpDirectory.mkdirs();

            Set<FileData> fileDataSet = sample.getFileDatas();

            File bamFile = WorkflowUtil.findFileByJobAndMimeTypeAndWorkflowId(getWorkflowBeanService()
                    .getMaPSeqDAOBean(), fileDataSet, PicardMarkDuplicates.class, MimeType.APPLICATION_BAM,
                    mergeBAMWorkflow.getId());

            String soughtAfterFileName = String.format("%s.merged.rg.deduped.bam", subjectName);

            // 2nd attempt to find bam file
            if (bamFile == null) {
                logger.debug("looking for: {}", soughtAfterFileName);
                for (FileData fileData : fileDataSet) {
                    if (fileData.getName().equals(soughtAfterFileName)) {
                        bamFile = new File(fileData.getPath(), fileData.getName());
                        break;
                    }
                }
            }

            // 3rd attempt to find bam file
            if (bamFile == null) {
                logger.debug("still looking for: {}", soughtAfterFileName);
                File mergeBAMOutputDirectory = new File(sample.getOutputDirectory(), "NCNEXUSMergeBAM");
                for (File outputDirFile : mergeBAMOutputDirectory.listFiles()) {
                    if (outputDirFile.getName().equals(soughtAfterFileName)) {
                        bamFile = outputDirFile;
                        break;
                    }
                }
            }

            if (bamFile == null) {
                logger.error("bam file to process was not found");
                throw new WorkflowException("bam file to process was not found");
            }

            if (bamFile != null && !bamFile.exists()) {
                logger.error("bam file doesn't exist");
                throw new WorkflowException("bam file doesn't exist");
            }

            try {

                // depth of coverage job
                CondorJobBuilder builder = WorkflowJobFactory
                        .createJob(++count, GATKDepthOfCoverageCLI.class, attempt.getId(), sample.getId())
                        .siteName(siteName).initialDirectory(outputDirectory.getAbsolutePath());
                builder.addArgument(GATKDepthOfCoverageCLI.INPUTFILE, bamFile.getAbsolutePath())
                        .addArgument(GATKDepthOfCoverageCLI.OUTPUTPREFIX,
                                bamFile.getName().replace(".bam", ".realign.fix.pr.coverage"))
                        .addArgument(GATKDepthOfCoverageCLI.KEY, GATKKey)
                        .addArgument(GATKDepthOfCoverageCLI.REFERENCESEQUENCE, referenceSequence)
                        .addArgument(GATKDepthOfCoverageCLI.PHONEHOME, GATKPhoneHomeType.NO_ET.toString())
                        .addArgument(GATKDepthOfCoverageCLI.DOWNSAMPLINGTYPE, GATKDownsamplingType.NONE.toString())
                        .addArgument(GATKDepthOfCoverageCLI.VALIDATIONSTRICTNESS, "LENIENT")
                        .addArgument(GATKDepthOfCoverageCLI.OMITDEPTHOUTPUTATEACHBASE)
                        .addArgument(GATKDepthOfCoverageCLI.INTERVALS, depthOfCoverageIntervalList);
                CondorJob dedupedRealignFixPrintReadsCoverageJob = builder.build();
                graph.addVertex(dedupedRealignFixPrintReadsCoverageJob);

                // unified genotyper job

                builder = WorkflowJobFactory
                        .createJob(++count, GATKUnifiedGenotyperCLI.class, attempt.getId(), sample.getId())
                        .siteName(siteName).numberOfProcessors(4);
                File dedupedRealignFixPrintReadsVcfFile = new File(outputDirectory, bamFile.getName().replace(".bam",
                        ".realign.fix.pr.vcf"));
                File gatkUnifiedGenotyperMetrics = new File(outputDirectory, bamFile.getName().replace(".bam",
                        ".metrics"));
                builder.addArgument(GATKUnifiedGenotyperCLI.INPUTFILE, bamFile.getAbsolutePath())
                        .addArgument(GATKUnifiedGenotyperCLI.OUT, dedupedRealignFixPrintReadsVcfFile.getAbsolutePath())
                        .addArgument(GATKUnifiedGenotyperCLI.KEY, GATKKey)
                        .addArgument(GATKUnifiedGenotyperCLI.INTERVALS, unifiedGenotyperIntervalList)
                        .addArgument(GATKUnifiedGenotyperCLI.REFERENCESEQUENCE, referenceSequence)
                        .addArgument(GATKUnifiedGenotyperCLI.DBSNP, unifiedGenotyperDBSNP)
                        .addArgument(GATKUnifiedGenotyperCLI.PHONEHOME, GATKPhoneHomeType.NO_ET.toString())
                        .addArgument(GATKUnifiedGenotyperCLI.DOWNSAMPLINGTYPE, GATKDownsamplingType.NONE.toString())
                        .addArgument(GATKUnifiedGenotyperCLI.GENOTYPELIKELIHOODSMODEL, "BOTH")
                        .addArgument(GATKUnifiedGenotyperCLI.OUTPUTMODE, "EMIT_ALL_SITES")
                        .addArgument(GATKUnifiedGenotyperCLI.ANNOTATION, "AlleleBalance")
                        .addArgument(GATKUnifiedGenotyperCLI.ANNOTATION, "DepthOfCoverage")
                        .addArgument(GATKUnifiedGenotyperCLI.ANNOTATION, "HomopolymerRun")
                        .addArgument(GATKUnifiedGenotyperCLI.ANNOTATION, "MappingQualityZero")
                        .addArgument(GATKUnifiedGenotyperCLI.ANNOTATION, "QualByDepth")
                        .addArgument(GATKUnifiedGenotyperCLI.ANNOTATION, "RMSMappingQuality")
                        .addArgument(GATKUnifiedGenotyperCLI.ANNOTATION, "HaplotypeScore")
                        .addArgument(GATKUnifiedGenotyperCLI.DOWNSAMPLETOCOVERAGE, "250")
                        .addArgument(GATKUnifiedGenotyperCLI.STANDCALLCONF, "4")
                        .addArgument(GATKUnifiedGenotyperCLI.STANDEMITCONF, "0")
                        .addArgument(GATKUnifiedGenotyperCLI.NUMTHREADS, "4")
                        .addArgument(GATKUnifiedGenotyperCLI.METRICS, gatkUnifiedGenotyperMetrics.getAbsolutePath());
                CondorJob dedupedRealignFixPrintReadsVcfJob = builder.build();
                graph.addVertex(dedupedRealignFixPrintReadsVcfJob);
                graph.addEdge(dedupedRealignFixPrintReadsCoverageJob, dedupedRealignFixPrintReadsVcfJob);

            } catch (Exception e) {
                throw new WorkflowException(e);
            }

        }

        return graph;
    }

    @Override
    public void postRun() throws WorkflowException {
        super.postRun();

        Set<Sample> sampleSet = getAggregatedSamples();

        for (Sample sample : sampleSet) {

            if ("Undetermined".equals(sample.getBarcode())) {
                continue;
            }

            ExecutorService es = Executors.newSingleThreadExecutor();

            SaveDepthOfCoverageAttributesRunnable docRunnable = new SaveDepthOfCoverageAttributesRunnable();
            docRunnable.setMapseqDAOBean(getWorkflowBeanService().getMaPSeqDAOBean());
            docRunnable.setSampleId(sample.getId());
            es.execute(docRunnable);

        }

    }

}
