package edu.unc.mapseq.ws;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.PropertyException;
import javax.xml.namespace.QName;
import javax.xml.ws.Service;
import javax.xml.ws.soap.SOAPBinding;

import org.apache.commons.io.IOUtils;
import org.junit.Test;

import edu.unc.mapseq.ws.ncnexus.variantcalling.NCNEXUSVariantCallingService;
import edu.unc.mapseq.ws.ncnexus.variantcalling.QualityControlInfo;

public class NCNEXUSVariantCallingServiceTest {

    @Test
    public void testCoverageResults() {
        QualityControlInfo ret = new QualityControlInfo();
        try {
            InputStream is = NCNEXUSVariantCallingServiceTest.class.getResourceAsStream("coverage.out");
            List<String> lines = IOUtils.readLines(is);

            for (String line : lines) {
                if (line.contains("Total")) {
                    String[] split = line.split("\t");
                    ret.setTotalCoverage(Long.valueOf(split[1]));
                    ret.setMean(Double.valueOf(split[2]));
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
        }

        assertTrue(ret.getMean() == 82.92D);
        assertTrue(ret.getTotalCoverage() == 6535241443L);

    }

    @Test
    public void testLookupQuantificationResults() {
        QName serviceQName = new QName("http://variantcalling.ncnexus.ws.mapseq.unc.edu", "NCNEXUSVariantCallingService");
        QName portQName = new QName("http://variantcalling.ncnexus.ws.mapseq.unc.edu", "NCNEXUSVariantCallingPort");
        Service service = Service.create(serviceQName);
        String host = "152.19.198.146";
        service.addPort(portQName, SOAPBinding.SOAP11HTTP_MTOM_BINDING,
                String.format("http://%s:%d/cxf/NCNEXUSVariantCallingService", host, 8181));
        NCNEXUSVariantCallingService webService = service.getPort(NCNEXUSVariantCallingService.class);

        QualityControlInfo results1 = webService.lookupQuantificationResults(2202538L);

        try {
            JAXBContext context = JAXBContext.newInstance(QualityControlInfo.class);
            Marshaller m = context.createMarshaller();
            m.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
            File resultsFile = new File("/tmp", "results.xml");
            FileWriter fw = new FileWriter(resultsFile);
            m.marshal(results1, fw);
        } catch (PropertyException e) {
            e.printStackTrace();
        } catch (JAXBException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

}
