package org.bustany.avcard;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentProviderOperation;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.AsyncTask;
import android.provider.ContactsContract;
import android.support.v7.app.ActionBarActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import net.sourceforge.cardme.engine.VCardEngine;
import net.sourceforge.cardme.vcard.VCard;
import net.sourceforge.cardme.vcard.types.AdrType;
import net.sourceforge.cardme.vcard.types.EmailType;
import net.sourceforge.cardme.vcard.types.NType;
import net.sourceforge.cardme.vcard.types.NoteType;
import net.sourceforge.cardme.vcard.types.OrgType;
import net.sourceforge.cardme.vcard.types.PhotoType;
import net.sourceforge.cardme.vcard.types.TelType;
import net.sourceforge.cardme.vcard.types.UrlType;
import net.sourceforge.cardme.vcard.types.params.AdrParamType;
import net.sourceforge.cardme.vcard.types.params.EmailParamType;
import net.sourceforge.cardme.vcard.types.params.TelParamType;
import net.sourceforge.cardme.vcard.types.params.UrlParamType;

import java.io.File;
import java.io.FileFilter;
import java.util.ArrayList;
import java.util.List;


public class MainActivity extends ActionBarActivity {
    private final static String LOG_TAG = "AVCard" ;
    private final static int REQUEST_CHOOSE_FOLDER = 1;

    private TextView importStatus = null;
    private Button importTestButton = null;
    private Button importButton = null;

    private File[] currentVcfFiles = null;

    private static class VcfFileFilter implements FileFilter {
        @Override
        public boolean accept(File file) {
            return file.getName().endsWith(".vcf");
        }
    }

    private class ListVcfTask extends AsyncTask<Uri, String, File[]> {
        @Override
        protected void onPreExecute() {
            currentVcfFiles = null;
            importTestButton.setEnabled(false);
            importButton.setEnabled(false);

            setImportStatus(getString(R.string.import_status_listing_folder));
        }

        @Override
        protected File[] doInBackground(Uri... uris) {
            if(uris.length != 1) {
                return new File[]{};
            }

            File f = new File(uris[0].getPath());

            if (!f.isDirectory()) {
                return new File[]{};
            }

           return f.listFiles(new VcfFileFilter());
        }

        @Override
        protected void onPostExecute(File[] files) {
            importTestButton.setEnabled(files.length > 0);

            if (files.length == 0) {
                setImportStatus(getString(R.string.import_status_no_vcf_files));
                return;
            }

            currentVcfFiles = files;

            setImportStatus(String.format(getString(R.string.import_status_found_vcf_files), files.length));
        }
    }

    private class ImportVcfTask extends AsyncTask<File, String, Boolean> {
        private boolean dryRun;

        public ImportVcfTask(boolean dryRun) {
            super();

            this.dryRun = dryRun;
        }

        @Override
        protected void onPreExecute() {
            importButton.setEnabled(false);
        }

        private String joinStringList(List<String> list, String j) {
            StringBuilder builder = new StringBuilder();
            boolean first = true;

            for (String s : list) {
                if (first) {
                    first = false;
                } else {
                    builder.append(j);
                }

                builder.append(s);
            }

            return builder.toString();
        }

        private ContentProviderOperation setContactName(VCard vcard) {
            ContentProviderOperation.Builder b = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI);
            b.withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0);
            b.withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE);

            boolean hasSomething = false;

            if (vcard.hasName()) {
                hasSomething = true;
                b.withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, vcard.getName().getName());
            }

            if (vcard.hasN()) {
                NType n = vcard.getN();

                if (n.hasGivenName()) {
                    hasSomething = true;
                    b.withValue(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, n.getGivenName());
                }

                if (n.hasFamilyName()) {
                    hasSomething = true;
                    b.withValue(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME, n.getFamilyName());
                }

                if (n.hasHonorificPrefixes()) {
                    hasSomething = true;
                    b.withValue(ContactsContract.CommonDataKinds.StructuredName.PREFIX, joinStringList(n.getHonorificPrefixes(), " "));
                }

                if (n.hasHonorificSuffixes()) {
                    hasSomething = true;
                    b.withValue(ContactsContract.CommonDataKinds.StructuredName.SUFFIX, joinStringList(n.getHonorificSuffixes(), " "));
                }

                if (n.hasAdditionalNames()) {
                    hasSomething = true;
                    b.withValue(ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME, joinStringList(n.getAdditionalNames(), " "));
                }
            }

            return (hasSomething ? b.build() : null);
        }

        private ContentProviderOperation setContactNickname(VCard vcard) {
            if (!vcard.hasNicknames()) {
                return null;
            }

            ContentProviderOperation.Builder b = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI);
            b.withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0);
            b.withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE);

            b.withValue(ContactsContract.CommonDataKinds.Nickname.NAME, joinStringList(vcard.getNicknames().getNicknames(), ", "));
            b.withValue(ContactsContract.CommonDataKinds.Nickname.TYPE, ContactsContract.CommonDataKinds.Nickname.TYPE_DEFAULT);

            return b.build();
        }

        private List<ContentProviderOperation> setContactEmails(VCard vcard) {
            List<ContentProviderOperation> ops = new ArrayList<ContentProviderOperation>();

            if (!vcard.hasEmails()) {
                return ops;
            }

            for (EmailType email : vcard.getEmails()) {
                if (!email.hasEmail()) {
                    continue;
                }

                ContentProviderOperation.Builder b = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI);
                b.withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0);
                b.withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE);

                b.withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email.getEmail());

                int addressType = ContactsContract.CommonDataKinds.Email.TYPE_OTHER;

                if (email.hasParams()) {
                    for (EmailParamType param : email.getParams()) {
                        if (param == EmailParamType.HOME) {
                            addressType = ContactsContract.CommonDataKinds.Email.TYPE_HOME;
                        }

                        if (param == EmailParamType.WORK) {
                            addressType = ContactsContract.CommonDataKinds.Email.TYPE_WORK;
                        }

                        if (param == EmailParamType.OTHER) {
                            addressType = ContactsContract.CommonDataKinds.Email.TYPE_OTHER;
                        }
                    }
                }

                b.withValue(ContactsContract.CommonDataKinds.Email.TYPE, addressType);

                ops.add(b.build());
            }

            return ops;
        }

        private List<ContentProviderOperation> setContactNotes(VCard vcard) {
            List<ContentProviderOperation> ops = new ArrayList<ContentProviderOperation>();

            if (!vcard.hasNotes()) {
                return ops;
            }

            for (NoteType note : vcard.getNotes()) {
                if (!note.hasNote()) {
                    continue;
                }

                ContentProviderOperation.Builder b = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI);
                b.withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0);
                b.withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE);

                b.withValue(ContactsContract.CommonDataKinds.Note.NOTE, note.getNote());

                ops.add(b.build());
            }

            return ops;
        }

        private ContentProviderOperation setContactOrg(VCard vcard) {
            if (!vcard.hasOrg()) {
                return null;
            }

            ContentProviderOperation.Builder b = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI);
            b.withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0);
            b.withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE);

            boolean hasSomething = false;

            OrgType o = vcard.getOrg();

            if (o.hasOrgName()) {
                hasSomething = true;
                b.withValue(ContactsContract.CommonDataKinds.Organization.COMPANY, o.getOrgName());
            }

            if (o.hasOrgUnits()) {
                hasSomething = true;
                b.withValue(ContactsContract.CommonDataKinds.Organization.DEPARTMENT, joinStringList(o.getOrgUnits(), ", "));
            }

            b.withValue(ContactsContract.CommonDataKinds.Organization.TYPE, ContactsContract.CommonDataKinds.Organization.TYPE_OTHER);

            return (hasSomething ? b.build() : null);
        }

        private List<ContentProviderOperation> setContactPhones(VCard vcard) {
            List<ContentProviderOperation> ops = new ArrayList<ContentProviderOperation>();

            if (!vcard.hasTels()) {
                return ops;
            }

            for (TelType tel : vcard.getTels()) {
                if (!tel.hasTelephone()) {
                    continue;
                }

                ContentProviderOperation.Builder b = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI);
                b.withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0);
                b.withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE);

                b.withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, tel.getTelephone());

                int telType = ContactsContract.CommonDataKinds.Phone.TYPE_OTHER;

                if (tel.hasParams()) {
                    for (TelParamType param : tel.getParams()) {
                        if (param == TelParamType.CAR) {
                            telType = ContactsContract.CommonDataKinds.Phone.TYPE_CAR;
                        } else if (param == TelParamType.CELL) {
                            telType = ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE;
                        } else if (param == TelParamType.FAX) {
                            telType = ContactsContract.CommonDataKinds.Phone.TYPE_OTHER_FAX;
                        } else if (param == TelParamType.HOME) {
                            telType = ContactsContract.CommonDataKinds.Phone.TYPE_HOME;
                        } else if (param == TelParamType.ISDN) {
                            telType = ContactsContract.CommonDataKinds.Phone.TYPE_ISDN;
                        } else if (param == TelParamType.PAGER) {
                            telType = ContactsContract.CommonDataKinds.Phone.TYPE_PAGER;
                        } else if (param == TelParamType.WORK) {
                            telType = ContactsContract.CommonDataKinds.Phone.TYPE_WORK;
                        } else {
                            telType = ContactsContract.CommonDataKinds.Phone.TYPE_OTHER;
                        }
                    }
                }

                b.withValue(ContactsContract.CommonDataKinds.Phone.TYPE, telType);

                ops.add(b.build());
            }

            return ops;
        }

        private List<ContentProviderOperation> setContactPhotos(VCard vcard) {
            List<ContentProviderOperation> ops = new ArrayList<ContentProviderOperation>();

            if (!vcard.hasPhotos()) {
                return ops;
            }

            for (PhotoType photo : vcard.getPhotos()) {
                if (!photo.hasPhoto() || photo.isURI()) {
                    continue;
                }

                ContentProviderOperation.Builder b = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI);
                b.withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0);
                b.withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE);

                b.withValue(ContactsContract.CommonDataKinds.Photo.PHOTO, photo.getPhoto());

                ops.add(b.build());
            }

            return ops;
        }

        private List<ContentProviderOperation> setContactAddresses(VCard vcard) {
            List<ContentProviderOperation> ops = new ArrayList<ContentProviderOperation>();

            if (!vcard.hasAdrs()) {
                return ops;
            }

            for (AdrType addr : vcard.getAdrs()) {
                ContentProviderOperation.Builder b = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI);
                b.withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0);
                b.withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE);

                boolean hasSomething = false;

                if (addr.hasCountryName()) {
                    hasSomething = true;
                    b.withValue(ContactsContract.CommonDataKinds.StructuredPostal.COUNTRY, addr.getCountryName());
                }

                if (addr.hasExtendedAddress()) {
                    hasSomething = true;
                    b.withValue(ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS, addr.getExtendedAddress());
                }

                if (addr.hasLocality()) {
                    hasSomething = true;
                    b.withValue(ContactsContract.CommonDataKinds.StructuredPostal.CITY, addr.getLocality());
                }

                if (addr.hasPostalCode()) {
                    hasSomething = true;
                    b.withValue(ContactsContract.CommonDataKinds.StructuredPostal.POSTCODE, addr.getPostalCode());
                }

                if (addr.hasPostOfficebox()) {
                    hasSomething = true;
                    b.withValue(ContactsContract.CommonDataKinds.StructuredPostal.POBOX, addr.getPostOfficeBox());
                }

                if (addr.hasRegion()) {
                    hasSomething = true;
                    b.withValue(ContactsContract.CommonDataKinds.StructuredPostal.REGION, addr.getRegion());
                }

                if (addr.hasStreetAddress()) {
                    hasSomething = true;
                    b.withValue(ContactsContract.CommonDataKinds.StructuredPostal.STREET, addr.getStreetAddress());
                }

                int addrType = ContactsContract.CommonDataKinds.StructuredPostal.TYPE_OTHER;

                if (addr.hasParams()) {
                    for (AdrParamType param : addr.getParams()) {
                        if (param == AdrParamType.DOM || param == AdrParamType.HOME) {
                            addrType = ContactsContract.CommonDataKinds.StructuredPostal.TYPE_HOME;
                        } else if (param == AdrParamType.WORK) {
                            addrType = ContactsContract.CommonDataKinds.StructuredPostal.TYPE_WORK;
                        } else {
                            addrType = ContactsContract.CommonDataKinds.StructuredPostal.TYPE_OTHER;
                        }
                    }
                }

                b.withValue(ContactsContract.CommonDataKinds.StructuredPostal.TYPE, addrType);

                if (!hasSomething) {
                    continue;
                }

                ops.add(b.build());
            }

            return ops;
        }

        private List<ContentProviderOperation> setContactUrls(VCard vcard) {
            List<ContentProviderOperation> ops = new ArrayList<ContentProviderOperation>();

            if (!vcard.hasUrls()) {
                return ops;
            }

            for (UrlType url : vcard.getUrls()) {
                if (!url.hasUrl() && !url.hasRawUrl()) {
                    continue;
                }

                ContentProviderOperation.Builder b = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI);
                b.withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0);
                b.withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE);

                String u = url.getRawUrl();

                if (u == null) {
                    u = url.getUrl().toString();
                }

                b.withValue(ContactsContract.CommonDataKinds.Website.URL, u);

                int urlType = ContactsContract.CommonDataKinds.Website.TYPE_OTHER;

                if (url.hasParams()) {
                    for (UrlParamType param : url.getParams()) {
                        if (param == UrlParamType.HOME) {
                            urlType = ContactsContract.CommonDataKinds.Website.TYPE_HOME;
                        } else if (param == UrlParamType.WORK) {
                            urlType = ContactsContract.CommonDataKinds.Website.TYPE_WORK;
                        } else {
                            urlType = ContactsContract.CommonDataKinds.Website.TYPE_OTHER;
                        }
                    }
                }

                b.withValue(ContactsContract.CommonDataKinds.Website.TYPE, urlType);

                ops.add(b.build());
            }

            return ops;
        }

        private void addToOps(List<ContentProviderOperation> ops, ContentProviderOperation op) {
            if (op == null) {
                return;
            }

            ops.add(op);
        }

        @Override
        protected Boolean doInBackground(File... files) {
            boolean allOk = true;

            VCardEngine engine = new VCardEngine();

            int processed = 0;

            VCard v;

            for (File f : files) {
                Log.d(LOG_TAG, "Parsing VCard for " + f.getName());

                try {
                    publishProgress((++processed) + "/" + files.length + " " + f.getName());
                    v = engine.parse(f);
                } catch (Exception e) {
                    allOk = false;
                    continue;
                }

                if (dryRun) {
                    continue;
                }

                Log.d(LOG_TAG, "Creating contact for " + f.getName());

                ArrayList<ContentProviderOperation> ops = new ArrayList<ContentProviderOperation>();

                ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, "org.bustany.AVCardContact")
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, "AVCard")
                    .build());

                addToOps(ops, setContactName(v));
                addToOps(ops, setContactNickname(v));
                ops.addAll(setContactEmails(v));
                ops.addAll(setContactNotes(v));
                addToOps(ops, setContactOrg(v));
                ops.addAll(setContactPhones(v));
                ops.addAll(setContactPhotos(v));
                ops.addAll(setContactAddresses(v));
                ops.addAll(setContactUrls(v));

                try {
                    getContentResolver().applyBatch(ContactsContract.AUTHORITY, ops);
                } catch (Exception e) {
                    allOk = false;
                    Log.e(LOG_TAG, "Error while inserting contact " + f.getName() + ": " + e);
                }

                Log.d(LOG_TAG, "Inserted contact for " + f.getName());
            }

            return allOk;
        }

        @Override
        protected void onProgressUpdate(String... reports) {
            if (reports.length != 1) {
                return;
            }

            setImportStatus(reports[0]);
        }

        @Override
        protected void onPostExecute(Boolean result) {
            if (dryRun) {
                importButton.setEnabled(result);
                setImportStatus(result ? getString(R.string.import_status_parse_success)
                        : getString(R.string.import_status_parse_failure));
            } else {
                setImportStatus(result ? getString(R.string.import_status_save_success)
                        : getString(R.string.import_status_save_failure));
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setTitle(getString(R.string.activity_title));
        // We lock to portrait since we don't want our AsyncTasks to get killed when the Activity
        // get recreated after an orientation change.
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        importStatus = (TextView) findViewById(R.id.label_import_status);
        importTestButton = (Button) findViewById(R.id.button_test_import);
        importButton = (Button) findViewById(R.id.button_import);
    }

    public void onChooseFolderClicked(View view) {
        Intent intent = new Intent("org.openintents.action.PICK_DIRECTORY");
        intent.putExtra("org.openintents.extra.TITLE", getString(R.string.folder_chooser_title));
        intent.putExtra("org.openintents.extra.BUTTON_TEXT", getString(R.string.folder_chooser_button));

        try {
            startActivityForResult(intent, REQUEST_CHOOSE_FOLDER);
        } catch (ActivityNotFoundException e) {
            setImportStatus(getString(R.string.import_status_no_chooser_activity));

            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setMessage(R.string.error_no_file_manager);
            AlertDialog d = builder.create();
            d.show();
        }
    }

    public void onImportClicked(View view) {
        if (currentVcfFiles == null) {
            setImportStatus(getString(R.string.import_status_no_files));
            return;
        }

        // This is invoked either by the "import" or "check vcards" button
        boolean dryRun = view == importTestButton;

        Log.d(LOG_TAG, "Starting import (dry run: " + dryRun + ")");
        new ImportVcfTask(dryRun).execute(currentVcfFiles);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CHOOSE_FOLDER) {
            if (resultCode != RESULT_OK) {
                setImportStatus(getString(R.string.import_status_chooser_failed));
                return;
            }

            Uri folderUri = data.getData();

            if (folderUri == null) {
                setImportStatus(getString(R.string.import_status_no_uri));
                return;
            }

            new ListVcfTask().execute(folderUri);
        }
    }

    private void setImportStatus(String str) {
        if (importStatus == null) {
            return;
        }

        importStatus.setText(str);
    }
}
