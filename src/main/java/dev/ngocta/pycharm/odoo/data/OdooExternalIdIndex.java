package dev.ngocta.pycharm.odoo.data;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.EverythingGlobalScope;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.xml.XmlFile;
import com.intellij.util.ArrayUtil;
import com.intellij.util.Processor;
import com.intellij.util.indexing.*;
import com.intellij.util.io.DataExternalizer;
import com.intellij.util.io.EnumeratorStringDescriptor;
import com.intellij.util.io.KeyDescriptor;
import com.jetbrains.python.psi.PyClass;
import com.jetbrains.python.psi.PyElementVisitor;
import com.jetbrains.python.psi.PyFile;
import dev.ngocta.pycharm.odoo.OdooNames;
import dev.ngocta.pycharm.odoo.csv.OdooCsvUtils;
import dev.ngocta.pycharm.odoo.python.model.OdooModelInfo;
import dev.ngocta.pycharm.odoo.python.model.OdooModelUtils;
import dev.ngocta.pycharm.odoo.python.module.OdooModule;
import dev.ngocta.pycharm.odoo.python.module.OdooModuleUtils;
import dev.ngocta.pycharm.odoo.xml.OdooXmlUtils;
import dev.ngocta.pycharm.odoo.xml.dom.OdooDomRecordLike;
import dev.ngocta.pycharm.odoo.xml.dom.OdooDomRoot;
import org.jetbrains.annotations.NotNull;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class OdooExternalIdIndex extends FileBasedIndexExtension<String, OdooRecord> {
    private static final ID<String, OdooRecord> NAME = ID.create("odoo.external.id");
    private static final OdooRecordCache RECORD_CACHE = new OdooRecordCache();
    private static final Key<Set<String>> IN_FILE_IDS = new Key<>("odooExternalIds");

    @NotNull
    @Override
    public ID<String, OdooRecord> getName() {
        return NAME;
    }

    @NotNull
    @Override
    public DataIndexer<String, OdooRecord, FileContent> getIndexer() {
        return inputData -> {
            Map<String, OdooRecord> result = new HashMap<>();
            Project project = inputData.getProject();
            VirtualFile file = inputData.getFile();
            VirtualFile moduleDirectory = OdooModuleUtils.getContainingOdooModuleDirectory(file);
            if (moduleDirectory == null) {
                return result;
            }
            List<OdooRecord> records = new LinkedList<>();
            PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
            if (psiFile instanceof XmlFile) {
                OdooDomRoot root = OdooXmlUtils.getOdooDataDomRoot((XmlFile) psiFile);
                if (root == null) {
                    return result;
                }
                List<OdooDomRecordLike> items = root.getAllRecordLikeItems();
                for (OdooDomRecordLike item : items) {
                    OdooRecord record = item.getRecord();
                    if (record != null) {
                        String id = record.getQualifiedId().trim();
                        if (!id.isEmpty()) {
                            records.add(record);
                        }
                    }
                }
            } else if (OdooCsvUtils.isCsvFile(file)) {
                OdooCsvUtils.processRecordInCsvFile(file, project, (record, lineNumber) -> {
                    records.add(record);
                    return true;
                });
            } else if (psiFile instanceof PyFile) {
                psiFile.acceptChildren(new PyElementVisitor() {
                    @Override
                    public void visitPyClass(PyClass cls) {
                        super.visitPyClass(cls);
                        OdooModelInfo info = OdooModelInfo.getInfo(cls);
                        if (info != null) {
                            String id = OdooModelUtils.getIrModelRecordId(info.getName());
                            OdooRecord record = new OdooRecord(id, OdooNames.IR_MODEL, moduleDirectory.getName(), null, null);
                            records.add(record);
                        }
                    }
                });
            }
            for (OdooRecord record : records) {
                result.put(record.getQualifiedId(), record.withoutDataFile());
                RECORD_CACHE.add(record);
            }
            Set<String> inFileIds = file.putUserDataIfAbsent(IN_FILE_IDS, ConcurrentHashMap.newKeySet());
            for (String id : inFileIds) {
                if (!result.containsKey(id)) {
                    RECORD_CACHE.clearCache(id, file);
                }
            }
            inFileIds.clear();
            inFileIds.addAll(result.keySet());
            return result;
        };
    }

    @NotNull
    @Override
    public KeyDescriptor<String> getKeyDescriptor() {
        return EnumeratorStringDescriptor.INSTANCE;
    }

    @NotNull
    @Override
    public DataExternalizer<OdooRecord> getValueExternalizer() {
        return new DataExternalizer<OdooRecord>() {
            @Override
            public void save(@NotNull DataOutput out,
                             OdooRecord value) throws IOException {
                out.writeUTF(value.getId());
                out.writeUTF(value.getModel());
                out.writeUTF(value.getModule());
                out.writeBoolean(value.getExtraInfo() != null);
                if (value.getExtraInfo() != null) {
                    value.getExtraInfo().write(out);
                }
            }

            @Override
            public OdooRecord read(@NotNull DataInput in) throws IOException {
                String id = in.readUTF();
                String model = in.readUTF();
                String module = in.readUTF();
                OdooRecordExtraInfo extraInfo = null;
                if (in.readBoolean()) {
                    extraInfo = OdooRecordExtraInfo.read(model, in);
                }
                return new OdooRecord(id, model, module, extraInfo, null);
            }
        };
    }

    @Override
    public int getVersion() {
        return 11;
    }

    @NotNull
    @Override
    public FileBasedIndex.InputFilter getInputFilter() {
        return file -> {
            String extension = file.getExtension();
            return extension != null
                    && ArrayUtil.contains(extension.toLowerCase().trim(), "csv", "xml", "py")
                    && OdooModuleUtils.isInOdooModule(file);
        };
    }

    @Override
    public boolean dependsOnFileContent() {
        return true;
    }

    @NotNull
    public static Collection<String> getAllIds(@NotNull Project project,
                                               @NotNull GlobalSearchScope scope) {
        List<String> ids = new LinkedList<>();
        FileBasedIndex.getInstance().processAllKeys(NAME, ids::add, scope, null);
        return ids;
    }

    private static boolean processRecordsByIds(@NotNull Project project,
                                               @NotNull GlobalSearchScope scope,
                                               @NotNull Processor<OdooRecord> processor,
                                               @NotNull Collection<String> ids) {
        FileBasedIndex index = FileBasedIndex.getInstance();
        GlobalSearchScope everythingScope = new EverythingGlobalScope(project);
        for (String id : ids) {
            if (!RECORD_CACHE.processRecords(id, processor, scope)) {
                if (!index.processValues(NAME, id, null, (file, value) -> {
                    file.putUserDataIfAbsent(IN_FILE_IDS, ConcurrentHashMap.newKeySet()).add(id);
                    OdooRecord record = value.withDataFile(file);
                    RECORD_CACHE.add(record);
                    if (scope.contains(file)) {
                        return processor.process(record);
                    }
                    return true;
                }, everythingScope)) {
                    return false;
                }
            }
        }
        return true;
    }

    public static boolean processAllRecords(@NotNull Project project,
                                            @NotNull GlobalSearchScope scope,
                                            @NotNull Processor<OdooRecord> processor) {
        Collection<String> ids = getAllIds(project, scope);
        return processRecordsByIds(project, scope, processor, ids);
    }

    @NotNull
    public static List<OdooRecord> findRecordsById(@NotNull String id,
                                                   @NotNull PsiElement anchor,
                                                   boolean allowUnqualified) {
        Project project = anchor.getProject();
        OdooModule odooModule = OdooModuleUtils.getContainingOdooModule(anchor);
        if (odooModule != null) {
            if (!id.contains(".")) {
                if (allowUnqualified) {
                    id = odooModule.getName() + "." + id;
                } else {
                    return Collections.emptyList();
                }
            }
            return findRecordsByQualifiedId(id, project, odooModule.getOdooModuleWithDependenciesScope());
        }
        return Collections.emptyList();
    }

    @NotNull
    public static List<OdooRecord> findRecordsByQualifiedId(@NotNull String id,
                                                            @NotNull PsiElement anchor) {
        return findRecordsById(id, anchor, false);
    }

    @NotNull
    public static List<OdooRecord> findRecordsByQualifiedId(@NotNull String id,
                                                            @NotNull Project project,
                                                            @NotNull GlobalSearchScope scope) {
        List<OdooRecord> records = new LinkedList<>();
        processRecordsByIds(project, scope, records::add, Collections.singleton(id));
        return records;
    }
}
