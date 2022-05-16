using System;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Threading.Tasks;
using Windows.Storage.Streams;
using Windows.UI.Xaml;
using Windows.UI.Xaml.Controls;
using NetMQ;
using NetMQ.Sockets;
using Windows.Networking.NetworkOperators;
using Windows.Storage;
using System.Collections.Generic;

// The Blank Page item template is documented at https://go.microsoft.com/fwlink/?LinkId=402352&clcid=0x409

namespace ZeroMQServer
{

    /// <summary>
    /// An empty page that can be used on its own or navigated to within a Frame.
    /// </summary>
    public sealed partial class MainPage : Page
    {
        private static readonly string StartMSG = "fetch";
        private static readonly string SyncMsg = "sync";
        private static readonly int chunks_size = 250000;
        private static readonly int chunk_count = 4;
        private Stream FileStream = null;
        private BufferedStream BufferedStream = null;
        private byte[] buffer;
        private string fileName = null;
        public MainPage()
        {
            this.InitializeComponent();
            buffer = new byte[chunks_size];
        }

        private async void Open_File_Click(object sender, RoutedEventArgs e)
        {
            var picker = new Windows.Storage.Pickers.FileOpenPicker();
            picker.ViewMode = Windows.Storage.Pickers.PickerViewMode.Thumbnail;
            picker.SuggestedStartLocation = Windows.Storage.Pickers.PickerLocationId.Downloads;
            picker.FileTypeFilter.Add("*");
            Windows.Storage.StorageFile file = await picker.PickSingleFileAsync();
            if (file != null)
            {
                // Application now has read/write access to the picked file

                FileStream = await file.OpenStreamForReadAsync();
                BufferedStream = new BufferedStream(FileStream);

                FileName.Text = "Picked file: " + file.Name + " " + FileStream.Length;
                fileName = file.Name;

            }
            else
            {
                FileName.Text = "Operation cancelled.";
            }
        }

        private void Button_Click(object sender, RoutedEventArgs e)
        {
            var server = new RouterSocket();
            //server.Bind("tcp://" + IPAddress.Text);
            server.Bind("tcp://192.168.31.34:5556");
            Debug.WriteLine("start server");
            bool isEnd = false;
            int index = 0;
            Task.Run(() =>
            {
                while (true)
                {
                    var fetchMessage = server.ReceiveMultipartMessage();
                    //[identity]["fetch"][chunk_size][chunk_count]
                    Debug.WriteLine("message.FrameCount={0}", fetchMessage.FrameCount);
                    string identity = fetchMessage.Pop().ConvertToString();
                    if (identity == null) break;
                    string command = fetchMessage.Pop().ConvertToString();
                    if (command.Equals(StartMSG))
                    {
                        int chunkSize = Convert.ToInt32(fetchMessage.Pop().ConvertToString());
                        int chunkCount = Convert.ToInt32(fetchMessage.Pop().ConvertToString());
                        Debug.WriteLine(" chunkSize: " + chunkSize + " " + FileStream.Length);

                        if (isEnd)
                        {
                            var dataMsg = new NetMQMessage();
                            dataMsg.Append(new NetMQFrame(Encoding.UTF8.GetBytes(identity)));
                            dataMsg.Append(new NetMQFrame(Encoding.UTF8.GetBytes("")));
                            dataMsg.Append(new NetMQFrame(Encoding.UTF8.GetBytes("-1")));
                            dataMsg.Append(new NetMQFrame(Encoding.UTF8.GetBytes("0")));
                            dataMsg.Append(new NetMQFrame(Encoding.UTF8.GetBytes("end")));
                            server.SendMultipartMessage(dataMsg);
                            continue;
                        }

                        while (chunkCount > 0)
                        {
                            int result = BufferedStream.Read(buffer, 0, chunkSize);

                            Debug.WriteLine(result);
                            var dataMsg = new NetMQMessage();
                            dataMsg.Append(new NetMQFrame(Encoding.UTF8.GetBytes(identity.ToString())));
                            dataMsg.Append(new NetMQFrame(buffer));
                            dataMsg.Append(new NetMQFrame(Encoding.UTF8.GetBytes(index.ToString())));
                            dataMsg.Append(new NetMQFrame(Encoding.UTF8.GetBytes(result.ToString())));
                            if (result < chunkSize)
                            {
                                isEnd = true;
                                dataMsg.Append(new NetMQFrame(Encoding.UTF8.GetBytes("end")));
                                server.SendMultipartMessage(dataMsg);
                                break;
                            }
                            server.SendMultipartMessage(dataMsg);
                            chunkCount--;
                            index++;
                        }
                    }
                    else if (command.Equals(SyncMsg))
                    {
                        var dataMsg = new NetMQMessage();
                        dataMsg.Append(new NetMQFrame(Encoding.UTF8.GetBytes(identity)));
                        dataMsg.Append(new NetMQFrame(Encoding.UTF8.GetBytes(fileName)));
                        dataMsg.Append(new NetMQFrame(Encoding.UTF8.GetBytes(FileStream.Length.ToString())));
                        server.SendMultipartMessage(dataMsg);
                    }

                }
            });

        }



        private async void Recv_File_Click(object sender, RoutedEventArgs e)
        {
            var client = new DealerSocket();
            ////server.Bind("tcp://" + IPAddress.Text);
            client.Options.Identity = Encoding.UTF8.GetBytes("uwpclient");
            client.Connect("tcp://192.168.31.23:5556");
            Debug.WriteLine("start connect to server");

            var msg = new NetMQMessage();
            msg.Append(new NetMQFrame(Encoding.UTF8.GetBytes(SyncMsg)));
            client.SendMultipartMessage(msg);
            Debug.WriteLine("send sync message");
            var syncmsg = client.ReceiveMultipartMessage();
            string filename = syncmsg.Pop().ConvertToString();
            int filesize = Convert.ToInt32(syncmsg.Pop().ConvertToString());
            var filetype = filename.Substring(filename.LastIndexOf("."));
            //currently need to save file use file picker due to no permission to create file directly
            var savePicker = new Windows.Storage.Pickers.FileSavePicker();
            savePicker.SuggestedStartLocation =
                Windows.Storage.Pickers.PickerLocationId.Downloads;
            // Dropdown of file types the user can save the file as
            savePicker.FileTypeChoices.Add("transfer file", new List<string>() { filetype });
            // Default file name if the user does not type one in or select a file to replace
            savePicker.SuggestedFileName = filename;
            Windows.Storage.StorageFile file = await savePicker.PickSaveFileAsync();
            if (file != null)
            {
                // Prevent updates to the remote version of the file until
                // we finish making changes and call CompleteUpdatesAsync.
                Windows.Storage.CachedFileManager.DeferUpdates(file);
                // write to file
                //await Windows.Storage.FileIO.WriteTextAsync(file, file.Name);
                // Let Windows know that we're finished changing the file so
                // the other app can update the remote version of the file.
                // Completing updates may require Windows to ask for user input.
                Windows.Storage.Provider.FileUpdateStatus status =
                    await Windows.Storage.CachedFileManager.CompleteUpdatesAsync(file);
                if (status == Windows.Storage.Provider.FileUpdateStatus.Complete)
                {
                    Debug.WriteLine("File " + file.Name + " was saved.");
                }
                else
                {
                    Debug.WriteLine("File " + file.Name + " couldn't be saved.");
                }
                DateTime start = DateTime.Now;
                int total = 0;
                int chunks = 0;
                bool shouldEnd = false;
                Stream fileStream = await file.OpenStreamForWriteAsync();
                await Task.Run(() =>
                {
                    while (true)
                    {
                        int count = chunk_count;
                        Debug.WriteLine("start fetch");
                        var fetchmsg = new NetMQMessage();
                        fetchmsg.Append(new NetMQFrame(Encoding.UTF8.GetBytes(StartMSG)));
                        fetchmsg.Append(new NetMQFrame(Encoding.UTF8.GetBytes(chunks_size.ToString())));
                        fetchmsg.Append(new NetMQFrame(Encoding.UTF8.GetBytes(chunk_count.ToString())));
                        client.SendMultipartMessage(fetchmsg);

                        while (count > 0)
                        {
                            var recvMsg = client.ReceiveMultipartMessage();
                            int msgSize = recvMsg.FrameCount;
                            var chunk = recvMsg.Pop();
                            msgSize--;
                            if (chunk == null)
                            {
                                break;
                            }
                            chunks++;
                            int length = chunk.MessageSize;
                            byte[] data = chunk.ToByteArray();
                            string indexMsg = recvMsg.Pop().ConvertToString();
                            msgSize--;
                            string realSizeMsg = recvMsg.Pop().ConvertToString();
                            msgSize--;
                            int index = Convert.ToInt32(indexMsg);
                            int realSize = Convert.ToInt32(realSizeMsg);
                            Debug.WriteLine(" write to file: " + index + " " + realSize);

                            fileStream.Write(data, 0, length);
                            total += length;
                            if (msgSize > 0)
                            {
                                Debug.WriteLine("end");
                                shouldEnd = true;
                                break;
                            }
                            recvMsg.Clear();
                            count--;
                        }
                        if (shouldEnd)
                        {
                            fileStream.Flush();
                            fileStream.Close();
                            break;
                        }
                    }
                    TimeSpan time = DateTime.Now - start;
                    Debug.WriteLine("total time: " + time.TotalMilliseconds);
                });
            }
            else
            {
                Debug.WriteLine("Operation cancelled.");
            }
        }
    }
}
